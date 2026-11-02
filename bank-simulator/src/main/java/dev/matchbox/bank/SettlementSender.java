package dev.matchbox.bank;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Settles approved charges after a delay by calling Matchbox's webhook, and deliberately gets some
 * wrong: a different amount, no settlement at all, or the same settlement twice.
 */
@Component
class SettlementSender {

	private static final Logger logger = LoggerFactory.getLogger(SettlementSender.class);

	private static final BigDecimal MISMATCH_DELTA = new BigDecimal("1.00");

	private static final Duration RETRY_STEP = Duration.ofSeconds(1);

	private final BankProperties properties;

	private final TaskScheduler scheduler;

	private final WebhookSigner signer;

	private final JsonMapper jsonMapper;

	private final BankStats stats;

	private final RestClient rest = RestClient.create();

	SettlementSender(BankProperties properties, TaskScheduler scheduler, WebhookSigner signer, JsonMapper jsonMapper,
			BankStats stats) {
		this.properties = properties;
		this.scheduler = scheduler;
		this.signer = signer;
		this.jsonMapper = jsonMapper;
		this.stats = stats;
	}

	void scheduleFor(Charge.Request charge) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		if (random.nextDouble() < this.properties.missingSettlementRate()) {
			this.stats.settlementsDropped.increment();
			return;
		}
		BigDecimal amount = charge.amount();
		if (random.nextDouble() < this.properties.amountMismatchRate()) {
			this.stats.amountsMismatched.increment();
			amount = amount.add(MISMATCH_DELTA);
		}
		Settlement settlement = new Settlement(charge.paymentId(), amount, charge.currency(),
				Instant.now().plus(this.properties.settlementDelay()));
		String body = this.jsonMapper.writeValueAsString(settlement);
		Instant due = settlement.settledAt();
		this.scheduler.schedule(() -> deliver(body, 1), due);
		if (random.nextDouble() < this.properties.duplicateSettlementRate()) {
			this.stats.duplicateSettlementsSent.increment();
			this.scheduler.schedule(() -> deliver(body, 1), due.plus(RETRY_STEP));
		}
	}

	private void deliver(String body, int attempt) {
		try {
			this.rest.post()
				.uri(this.properties.settlementWebhookUrl())
				.contentType(MediaType.APPLICATION_JSON)
				.header(WebhookSigner.SIGNATURE_HEADER, this.signer.sign(body))
				.body(body)
				.retrieve()
				.toBodilessEntity();
			this.stats.settlementsDelivered.increment();
		}
		catch (RestClientException ex) {
			if (attempt >= this.properties.maxDeliveryAttempts()) {
				this.stats.deliveryFailures.increment();
				logger.warn("Giving up on settlement after {} attempts: {}", attempt, body, ex);
				return;
			}
			this.scheduler.schedule(() -> deliver(body, attempt + 1),
					Instant.now().plus(RETRY_STEP.multipliedBy(attempt)));
		}
	}

}
