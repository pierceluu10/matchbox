package dev.matchbox.bank;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Rates are probabilities in [0, 1]; they control how often the bank misbehaves. */
@ConfigurationProperties("matchbox.bank")
record BankProperties(double declineRate, double transientFailureRate, double amountMismatchRate,
		double missingSettlementRate, double duplicateSettlementRate, Duration settlementDelay,
		int maxDeliveryAttempts, URI settlementWebhookUrl, String webhookSecret) {
}
