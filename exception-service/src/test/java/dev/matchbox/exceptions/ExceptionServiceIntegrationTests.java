package dev.matchbox.exceptions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import dev.matchbox.shared.KafkaPostgresContainers;
import dev.matchbox.shared.TopicReader;
import dev.matchbox.shared.events.ExceptionStatus;
import dev.matchbox.shared.events.ExceptionType;
import dev.matchbox.shared.events.ExceptionUpdated;
import dev.matchbox.shared.events.ReconciliationException;
import dev.matchbox.shared.events.Topics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "matchbox.outbox.poll-interval-ms=10")
@Import(KafkaPostgresContainers.class)
class ExceptionServiceIntegrationTests {

	private static final Duration WAIT = Duration.ofSeconds(20);

	private static final ParameterizedTypeReference<List<ExceptionCase>> CASES = new ParameterizedTypeReference<>() {
	};

	@LocalServerPort
	int port;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	ConsumerFactory<String, String> consumerFactory;

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
	void replayedExceptionOpensOneCaseAndNotifiesThePaymentServiceOnce() {
		UUID paymentId = UUID.randomUUID();

		for (int copy = 0; copy < 3; copy++) {
			publish(paymentId, ExceptionType.AMOUNT_MISMATCH);
		}

		ExceptionCase opened = awaitCase(paymentId);
		assertThat(opened.status()).isEqualTo(ExceptionStatus.OPEN);
		assertThat(opened.expectedAmount()).isEqualByComparingTo("20.00");
		assertThat(opened.recommendedAction()).isEqualTo(Playbook.recommendedAction(ExceptionType.AMOUNT_MISMATCH));
		assertThat(updatesFor(paymentId)).singleElement()
			.satisfies((update) -> assertThat(update.status()).isEqualTo(ExceptionStatus.OPEN));
		assertThat(casesFor(paymentId)).hasSize(1);
	}

	@Test
	void resolvingACasePublishesTheResolutionAndCannotBeRepeated() {
		UUID paymentId = UUID.randomUUID();
		publish(paymentId, ExceptionType.MISSING_SETTLEMENT);
		ExceptionCase opened = awaitCase(paymentId);

		ResponseEntity<ExceptionCase> resolved = resolve(opened.exceptionId(), "Bank re-sent the settlement");

		assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resolved.getBody().status()).isEqualTo(ExceptionStatus.RESOLVED);
		assertThat(resolved.getBody().resolutionNote()).isEqualTo("Bank re-sent the settlement");
		assertThat(updatesFor(paymentId)).extracting(ExceptionUpdated::status)
			.containsExactly(ExceptionStatus.OPEN, ExceptionStatus.RESOLVED);
		assertThat(resolveStatus(opened.exceptionId(), "again")).isEqualTo(HttpStatus.CONFLICT);
		assertThat(resolveStatus(UUID.randomUUID(), "nope")).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(resolveStatus(opened.exceptionId(), " ")).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void differentExceptionTypesForOnePaymentAreSeparateCases() {
		UUID paymentId = UUID.randomUUID();

		publish(paymentId, ExceptionType.AMOUNT_MISMATCH);
		publish(paymentId, ExceptionType.UNEXPECTED_SETTLEMENT);

		await().atMost(WAIT).until(() -> casesFor(paymentId).size() == 2);
	}

	@Test
	void listsByStatusAndSummarizesCounts() {
		UUID paymentId = UUID.randomUUID();
		publish(paymentId, ExceptionType.UNEXPECTED_SETTLEMENT);
		awaitCase(paymentId);

		List<ExceptionCase> open = this.http.get()
			.uri("/exceptions?status=OPEN&type=UNEXPECTED_SETTLEMENT")
			.retrieve()
			.body(CASES);
		List<ExceptionSummary> summary = this.http.get()
			.uri("/exceptions/summary")
			.retrieve()
			.body(new ParameterizedTypeReference<>() {
			});

		assertThat(open).extracting(ExceptionCase::paymentId).contains(paymentId);
		assertThat(open).allSatisfy((exceptionCase) -> {
			assertThat(exceptionCase.status()).isEqualTo(ExceptionStatus.OPEN);
			assertThat(exceptionCase.type()).isEqualTo(ExceptionType.UNEXPECTED_SETTLEMENT);
		});
		assertThat(summary).anySatisfy((row) -> {
			assertThat(row.type()).isEqualTo(ExceptionType.UNEXPECTED_SETTLEMENT);
			assertThat(row.status()).isEqualTo(ExceptionStatus.OPEN);
			assertThat(row.count()).isPositive();
		});
		assertThat(this.http.get().uri("/exceptions?limit=0").retrieve().toBodilessEntity().getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void malformedEventGoesStraightToTheDeadLetterTopic() {
		String key = "poison-" + UUID.randomUUID();

		this.kafka.send(Topics.RECONCILIATION_EXCEPTION, key, "[]");

		assertThat(TopicReader.awaitRecords(this.consumerFactory,
				Topics.RECONCILIATION_EXCEPTION + Topics.DEAD_LETTER_SUFFIX, key))
			.hasSize(1);
	}

	private void publish(UUID paymentId, ExceptionType type) {
		this.kafka.send(Topics.RECONCILIATION_EXCEPTION, paymentId.toString(),
				this.jsonMapper.writeValueAsString(new ReconciliationException(paymentId, type,
						new BigDecimal("20.00"), new BigDecimal("21.00"), Instant.now())));
	}

	private List<ExceptionCase> casesFor(UUID paymentId) {
		return this.http.get()
			.uri("/exceptions?limit=500")
			.retrieve()
			.body(CASES)
			.stream()
			.filter((exceptionCase) -> exceptionCase.paymentId().equals(paymentId))
			.toList();
	}

	private ExceptionCase awaitCase(UUID paymentId) {
		await().atMost(WAIT).until(() -> !casesFor(paymentId).isEmpty());
		return casesFor(paymentId).getFirst();
	}

	private List<ExceptionUpdated> updatesFor(UUID paymentId) {
		return TopicReader.awaitRecords(this.consumerFactory, Topics.EXCEPTION_UPDATED, paymentId.toString())
			.stream()
			.map((record) -> this.jsonMapper.readValue(record.value(), ExceptionUpdated.class))
			.toList();
	}

	private ResponseEntity<ExceptionCase> resolve(UUID exceptionId, String note) {
		return this.http.post()
			.uri("/exceptions/{id}/resolve", exceptionId)
			.body(Map.of("note", note))
			.retrieve()
			.toEntity(ExceptionCase.class);
	}

	private HttpStatusCode resolveStatus(UUID exceptionId, String note) {
		return this.http.post()
			.uri("/exceptions/{id}/resolve", exceptionId)
			.body(Map.of("note", note))
			.retrieve()
			.toBodilessEntity()
			.getStatusCode();
	}

}
