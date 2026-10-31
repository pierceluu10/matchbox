package dev.matchbox.reconciliation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import dev.matchbox.shared.KafkaPostgresContainers;
import dev.matchbox.shared.TopicReader;
import dev.matchbox.shared.events.ChargeOutcome;
import dev.matchbox.shared.events.ExceptionType;
import dev.matchbox.shared.events.PaymentProcessed;
import dev.matchbox.shared.events.ReconciliationException;
import dev.matchbox.shared.events.Topics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "matchbox.outbox.poll-interval-ms=10", "matchbox.reconciliation.settlement-window=2s",
				"matchbox.reconciliation.sweep-interval-ms=200",
				"matchbox.reconciliation.webhook-secret=" + ReconciliationIntegrationTests.SECRET })
@Import(KafkaPostgresContainers.class)
class ReconciliationIntegrationTests {

	static final String SECRET = "test-secret";

	private static final Duration WAIT = Duration.ofSeconds(20);

	private static final Duration PAST_WINDOW = Duration.ofSeconds(4);

	@LocalServerPort
	int port;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	ConsumerFactory<String, String> consumerFactory;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper jsonMapper;

	RestClient http;

	@BeforeEach
	void setUp() {
		this.http = RestClient.builder()
			.baseUrl("http://localhost:" + this.port)
			.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
			})
			.build();
	}

	@Test
	void paymentThenMatchingSettlementReconcilesWithoutAnException() {
		UUID paymentId = UUID.randomUUID();

		publishProcessed(paymentId, ChargeOutcome.CHARGED, "20.00");
		awaitOurSide(paymentId);
		assertThat(settle(paymentId, "20.00")).isEqualTo(HttpStatus.OK);

		assertThat(state(paymentId)).isEqualTo("MATCHED");
		await().during(PAST_WINDOW).atMost(PAST_WINDOW.plusSeconds(2)).until(() -> "MATCHED".equals(state(paymentId)));
	}

	@Test
	void settlementArrivingBeforeThePaymentStillReconciles() {
		UUID paymentId = UUID.randomUUID();

		assertThat(settle(paymentId, "20.00")).isEqualTo(HttpStatus.OK);
		publishProcessed(paymentId, ChargeOutcome.CHARGED, "20.00");

		await().atMost(WAIT).until(() -> "MATCHED".equals(state(paymentId)));
	}

	@Test
	void amountMismatchRaisesExactlyOneExceptionEvenWhenBothSidesAreReplayed() {
		UUID paymentId = UUID.randomUUID();

		publishProcessed(paymentId, ChargeOutcome.CHARGED, "20.00");
		publishProcessed(paymentId, ChargeOutcome.CHARGED, "20.00");
		awaitOurSide(paymentId);
		settle(paymentId, "21.00");
		settle(paymentId, "21.00");

		List<ReconciliationException> raised = exceptionsFor(paymentId);
		assertThat(raised).hasSize(1);
		assertThat(raised.getFirst().type()).isEqualTo(ExceptionType.AMOUNT_MISMATCH);
		assertThat(raised.getFirst().expectedAmount()).isEqualByComparingTo("20.00");
		assertThat(raised.getFirst().settledAmount()).isEqualByComparingTo("21.00");
	}

	@Test
	void chargedPaymentWithNoSettlementBecomesMissingAfterTheWindow() {
		UUID paymentId = UUID.randomUUID();

		publishProcessed(paymentId, ChargeOutcome.CHARGED, "30.00");

		List<ReconciliationException> raised = exceptionsFor(paymentId);
		assertThat(raised).singleElement()
			.satisfies((exception) -> assertThat(exception.type()).isEqualTo(ExceptionType.MISSING_SETTLEMENT));
	}

	@Test
	void settlementForAPaymentWeNeverProcessedBecomesUnexpectedAfterTheWindow() {
		UUID paymentId = UUID.randomUUID();

		settle(paymentId, "40.00");

		assertThat(exceptionsFor(paymentId)).singleElement()
			.satisfies((exception) -> assertThat(exception.type()).isEqualTo(ExceptionType.UNEXPECTED_SETTLEMENT));
	}

	@Test
	void settlementOfAFailedPaymentIsUnexpected() {
		UUID paymentId = UUID.randomUUID();

		publishProcessed(paymentId, ChargeOutcome.FAILED, "15.00");
		awaitOurSide(paymentId);
		settle(paymentId, "15.00");

		assertThat(exceptionsFor(paymentId)).singleElement()
			.satisfies((exception) -> assertThat(exception.type()).isEqualTo(ExceptionType.UNEXPECTED_SETTLEMENT));
	}

	@Test
	void failedPaymentWithNoSettlementClosesCleanlyAfterTheWindow() {
		UUID paymentId = UUID.randomUUID();

		publishProcessed(paymentId, ChargeOutcome.FAILED, "15.00");

		await().atMost(WAIT).until(() -> "MATCHED".equals(state(paymentId)));
	}

	@Test
	void webhookRejectsUnsignedAndMalformedSettlements() {
		String body = settlementJson(UUID.randomUUID(), "10.00");

		assertThat(post(body, null)).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(post(body, "00ff")).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(post(body, "not-hex")).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(post("{bad", sign("{bad"))).isEqualTo(HttpStatus.BAD_REQUEST);
		String missingAmount = "{\"paymentId\":\"" + UUID.randomUUID() + "\",\"currency\":\"USD\"}";
		assertThat(post(missingAmount, sign(missingAmount))).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void malformedEventGoesStraightToTheDeadLetterTopic() {
		String key = "poison-" + UUID.randomUUID();

		this.kafka.send(Topics.PAYMENT_PROCESSED, key, "{\"paymentId\":null}");

		assertThat(TopicReader.awaitRecords(this.consumerFactory, Topics.PAYMENT_PROCESSED + Topics.DEAD_LETTER_SUFFIX,
				key))
			.hasSize(1);
	}

	private void publishProcessed(UUID paymentId, ChargeOutcome outcome, String amount) {
		this.kafka.send(Topics.PAYMENT_PROCESSED, paymentId.toString(), this.jsonMapper.writeValueAsString(
				new PaymentProcessed(paymentId, outcome, new BigDecimal(amount), "USD", Instant.now())));
	}

	private void awaitOurSide(UUID paymentId) {
		await().atMost(WAIT)
			.until(() -> this.jdbc
				.sql("SELECT count(*) FROM reconciliation_records WHERE payment_id = :id AND our_outcome IS NOT NULL")
				.param("id", paymentId)
				.query(Long.class)
				.single() == 1);
	}

	private HttpStatusCode settle(UUID paymentId, String amount) {
		String body = settlementJson(paymentId, amount);
		return post(body, sign(body));
	}

	private String settlementJson(UUID paymentId, String amount) {
		return this.jsonMapper
			.writeValueAsString(new Settlement(paymentId, new BigDecimal(amount), "USD", Instant.now()));
	}

	private HttpStatusCode post(String body, String signature) {
		RestClient.RequestBodySpec spec = this.http.post()
			.uri("/settlements")
			.contentType(MediaType.APPLICATION_JSON);
		if (signature != null) {
			spec.header(WebhookVerifier.SIGNATURE_HEADER, signature);
		}
		return spec.body(body).retrieve().toBodilessEntity().getStatusCode();
	}

	private String state(UUID paymentId) {
		return this.jdbc.sql("SELECT state FROM reconciliation_records WHERE payment_id = :id")
			.param("id", paymentId)
			.query(String.class)
			.optional()
			.orElse(null);
	}

	private List<ReconciliationException> exceptionsFor(UUID paymentId) {
		List<ConsumerRecord<String, String>> records = TopicReader.awaitRecords(this.consumerFactory,
				Topics.RECONCILIATION_EXCEPTION, paymentId.toString());
		return records.stream()
			.map((record) -> this.jsonMapper.readValue(record.value(), ReconciliationException.class))
			.toList();
	}

	private static String sign(String body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
