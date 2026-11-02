package dev.matchbox.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import dev.matchbox.shared.KafkaPostgresContainers;
import dev.matchbox.shared.TopicReader;
import dev.matchbox.shared.events.ChargeOutcome;
import dev.matchbox.shared.events.ExceptionStatus;
import dev.matchbox.shared.events.ExceptionType;
import dev.matchbox.shared.events.ExceptionUpdated;
import dev.matchbox.shared.events.PaymentProcessed;
import dev.matchbox.shared.events.PaymentRequested;
import dev.matchbox.shared.events.Topics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "matchbox.outbox.poll-interval-ms=10")
@Import(KafkaPostgresContainers.class)
class PaymentFlowIntegrationTests {

	private static final Duration WAIT = Duration.ofSeconds(20);

	private static final Duration REPLAY_SETTLE = Duration.ofSeconds(2);

	@LocalServerPort
	int port;

	@MockitoBean
	BankClient bank;

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
		when(this.bank.charge(any(), any(), any())).thenReturn(BankClient.ChargeDecision.APPROVED);
	}

	@Test
	void acceptsPaymentThenChargesItAsynchronouslyAndPublishesTheOutcome() {
		ResponseEntity<PaymentAccepted> response = post(validPayment(), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		UUID paymentId = response.getBody().paymentId();
		assertThat(response.getBody().status()).isEqualTo(PaymentStatus.PENDING);
		assertThat(response.getHeaders().getLocation()).hasPath("/payments/" + paymentId);
		assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");

		awaitStatus(paymentId, PaymentStatus.CHARGED);
		verify(this.bank, times(1)).charge(eq(paymentId), any(), eq("USD"));
		List<ConsumerRecord<String, String>> processed = TopicReader.awaitRecords(this.consumerFactory,
				Topics.PAYMENT_PROCESSED, paymentId.toString());
		PaymentProcessed event = this.jsonMapper.readValue(processed.getFirst().value(), PaymentProcessed.class);
		assertThat(event.outcome()).isEqualTo(ChargeOutcome.CHARGED);
		assertThat(event.amount()).isEqualByComparingTo("42.50");
	}

	@Test
	void replayedPaymentRequestedEventsNeverChargeTwice() {
		UUID paymentId = post(validPayment(), null).getBody().paymentId();
		awaitStatus(paymentId, PaymentStatus.CHARGED);

		String replay = this.jsonMapper.writeValueAsString(
				new PaymentRequested(paymentId, "customer-1", new BigDecimal("42.50"), "USD", Instant.now()));
		this.kafka.send(Topics.PAYMENT_REQUESTED, paymentId.toString(), replay);
		this.kafka.send(Topics.PAYMENT_REQUESTED, paymentId.toString(), replay);

		await().during(REPLAY_SETTLE)
			.atMost(REPLAY_SETTLE.plusSeconds(1))
			.untilAsserted(() -> verify(this.bank, times(1)).charge(eq(paymentId), any(), any()));
		assertThat(countProcessedEvents(paymentId)).isOne();
	}

	@Test
	void reusedIdempotencyKeyReturnsTheOriginalPaymentAndRejectsADifferentBody() {
		String key = UUID.randomUUID().toString();

		UUID first = post(validPayment(), key).getBody().paymentId();
		UUID second = post(validPayment(), key).getBody().paymentId();

		assertThat(second).isEqualTo(first);
		assertThat(this.jdbc.sql("SELECT count(*) FROM payments WHERE idempotency_key = :key")
			.param("key", key)
			.query(Long.class)
			.single()).isOne();
		ResponseEntity<String> conflict = this.http.post()
			.uri("/payments")
			.header(PaymentController.IDEMPOTENCY_KEY_HEADER, key)
			.body(Map.of("customerId", "customer-1", "amount", "99.00", "currency", "USD"))
			.retrieve()
			.toEntity(String.class);
		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void rejectsInvalidPaymentsAtTheApi() {
		assertThat(postRaw(Map.of("customerId", "customer-1", "amount", "-5", "currency", "USD")))
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(postRaw(Map.of("customerId", "customer-1", "amount", "10.001", "currency", "USD")))
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(postRaw(Map.of("customerId", "customer-1", "amount", "10", "currency", "usd")))
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(postRaw(Map.of("amount", "10", "currency", "USD"))).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void retriesTransientBankFailuresUntilTheChargeSucceeds() {
		when(this.bank.charge(any(), any(), any()))
			.thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
			.thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
			.thenReturn(BankClient.ChargeDecision.APPROVED);

		UUID paymentId = post(validPayment(), null).getBody().paymentId();

		awaitStatus(paymentId, PaymentStatus.CHARGED);
		verify(this.bank, times(3)).charge(eq(paymentId), any(), any());
	}

	@Test
	void declinedChargeMarksThePaymentFailed() {
		when(this.bank.charge(any(), any(), any())).thenReturn(BankClient.ChargeDecision.DECLINED);

		UUID paymentId = post(validPayment(), null).getBody().paymentId();

		awaitStatus(paymentId, PaymentStatus.FAILED);
	}

	@Test
	void malformedEventGoesStraightToTheDeadLetterTopic() {
		String key = "poison-" + UUID.randomUUID();

		this.kafka.send(Topics.PAYMENT_REQUESTED, key, "{not json");

		List<ConsumerRecord<String, String>> dead = TopicReader.awaitRecords(this.consumerFactory,
				Topics.PAYMENT_REQUESTED + Topics.DEAD_LETTER_SUFFIX, key);
		assertThat(dead.getFirst().value()).isEqualTo("{not json");
	}

	@Test
	void exceptionUpdatesReachThePayment() {
		UUID paymentId = post(validPayment(), null).getBody().paymentId();
		awaitStatus(paymentId, PaymentStatus.CHARGED);
		assertThat(get(paymentId).exceptionStatus()).isNull();

		this.kafka.send(Topics.EXCEPTION_UPDATED, paymentId.toString(),
				this.jsonMapper.writeValueAsString(new ExceptionUpdated(paymentId, UUID.randomUUID(),
						ExceptionType.AMOUNT_MISMATCH, ExceptionStatus.OPEN, Instant.now())));

		await().atMost(WAIT)
			.untilAsserted(() -> assertThat(get(paymentId).exceptionStatus()).isEqualTo(ExceptionStatus.OPEN));
	}

	@Test
	void unknownPaymentIsNotFound() {
		ResponseEntity<String> response = this.http.get()
			.uri("/payments/{id}", UUID.randomUUID())
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	private CreatePaymentRequest validPayment() {
		return new CreatePaymentRequest("customer-1", new BigDecimal("42.50"), "USD");
	}

	private ResponseEntity<PaymentAccepted> post(CreatePaymentRequest request, String idempotencyKey) {
		RestClient.RequestBodySpec spec = this.http.post().uri("/payments");
		if (idempotencyKey != null) {
			spec.header(PaymentController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
		}
		return spec.body(request).retrieve().toEntity(PaymentAccepted.class);
	}

	private HttpStatusCode postRaw(Map<String, String> body) {
		return this.http.post().uri("/payments").body(body).retrieve().toBodilessEntity().getStatusCode();
	}

	private Payment get(UUID paymentId) {
		return this.http.get().uri("/payments/{id}", paymentId).retrieve().body(Payment.class);
	}

	private void awaitStatus(UUID paymentId, PaymentStatus status) {
		await().atMost(WAIT).untilAsserted(() -> assertThat(get(paymentId).status()).isEqualTo(status));
	}

	private long countProcessedEvents(UUID paymentId) {
		return TopicReader.awaitRecords(this.consumerFactory, Topics.PAYMENT_PROCESSED, paymentId.toString()).size();
	}

}
