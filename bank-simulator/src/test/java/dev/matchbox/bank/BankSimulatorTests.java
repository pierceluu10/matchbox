package dev.matchbox.bank;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BankSimulatorTests {

	private static final Duration WAIT = Duration.ofSeconds(10);

	private static final Duration QUIET_PERIOD = Duration.ofSeconds(1);

	static final WebhookReceiver webhook = startReceiver();

	@AfterAll
	static void stopReceiver() {
		webhook.close();
	}

	static void baseProperties(DynamicPropertyRegistry registry) {
		registry.add("matchbox.bank.settlement-webhook-url", webhook::url);
		registry.add("matchbox.bank.settlement-delay", () -> "100ms");
		registry.add("matchbox.bank.decline-rate", () -> "0");
		registry.add("matchbox.bank.missing-settlement-rate", () -> "0");
	}

	static Charge.Request chargeOf(String amount) {
		return new Charge.Request(UUID.randomUUID(), new BigDecimal(amount), "USD");
	}

	static ResponseEntity<Charge.Response> post(int port, Charge.Request request) {
		return RestClient.builder()
			.baseUrl("http://localhost:" + port)
			.defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
			})
			.build()
			.post()
			.uri("/charges")
			.body(request)
			.retrieve()
			.toEntity(Charge.Response.class);
	}

	static long deliveriesFor(UUID paymentId) {
		return webhook.deliveries.stream().filter((d) -> d.body().contains(paymentId.toString())).count();
	}

	private static WebhookReceiver startReceiver() {
		try {
			return new WebhookReceiver();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
	class WellBehaved {

		@LocalServerPort
		int port;

		@Autowired
		WebhookSigner signer;

		@Autowired
		JsonMapper jsonMapper;

		@DynamicPropertySource
		static void properties(DynamicPropertyRegistry registry) {
			baseProperties(registry);
			registry.add("matchbox.bank.transient-failure-rate", () -> "0");
			registry.add("matchbox.bank.amount-mismatch-rate", () -> "0");
			registry.add("matchbox.bank.duplicate-settlement-rate", () -> "0");
		}

		@Test
		void repeatedChargeReturnsTheOriginalDecisionAndSettlesExactlyOnceWithAValidSignature() {
			Charge.Request charge = chargeOf("25.00");

			Charge.Response first = post(this.port, charge).getBody();
			Charge.Response second = post(this.port, charge).getBody();

			assertThat(first.decision()).isEqualTo(Charge.Decision.APPROVED);
			assertThat(second).isEqualTo(first);
			await().atMost(WAIT).until(() -> deliveriesFor(charge.paymentId()) == 1);
			await().during(QUIET_PERIOD).until(() -> deliveriesFor(charge.paymentId()) == 1);
			WebhookReceiver.Delivery delivery = webhook.deliveries.stream()
				.filter((d) -> d.body().contains(charge.paymentId().toString()))
				.findFirst()
				.orElseThrow();
			assertThat(delivery.signature()).isEqualTo(this.signer.sign(delivery.body()));
			assertThat(this.jsonMapper.readValue(delivery.body(), Settlement.class).amount())
				.isEqualByComparingTo("25.00");
		}

		@Test
		void rejectsInvalidChargeRequests() {
			assertThat(post(this.port, new Charge.Request(null, new BigDecimal("1"), "USD")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		}

	}

	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
	class Misbehaving {

		@LocalServerPort
		int port;

		@Autowired
		JsonMapper jsonMapper;

		@DynamicPropertySource
		static void properties(DynamicPropertyRegistry registry) {
			baseProperties(registry);
			registry.add("matchbox.bank.transient-failure-rate", () -> "0");
			registry.add("matchbox.bank.amount-mismatch-rate", () -> "1");
			registry.add("matchbox.bank.duplicate-settlement-rate", () -> "1");
		}

		@Test
		void settlesTheWrongAmountAndSendsItTwice() {
			Charge.Request charge = chargeOf("10.00");

			post(this.port, charge);

			await().atMost(WAIT).until(() -> deliveriesFor(charge.paymentId()) == 2);
			webhook.deliveries.stream()
				.filter((d) -> d.body().contains(charge.paymentId().toString()))
				.forEach((d) -> assertThat(this.jsonMapper.readValue(d.body(), Settlement.class).amount())
					.isEqualByComparingTo("11.00"));
		}

	}

	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
	class Unavailable {

		@LocalServerPort
		int port;

		@DynamicPropertySource
		static void properties(DynamicPropertyRegistry registry) {
			baseProperties(registry);
			registry.add("matchbox.bank.transient-failure-rate", () -> "1");
		}

		@Test
		void failsTransientlyWithServiceUnavailableAndRecordsNoCharge() {
			Charge.Request charge = chargeOf("5.00");

			assertThat(post(this.port, charge).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			await().during(QUIET_PERIOD).until(() -> deliveriesFor(charge.paymentId()) == 0);
		}

	}

}
