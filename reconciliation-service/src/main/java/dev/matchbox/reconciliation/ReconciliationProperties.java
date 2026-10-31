package dev.matchbox.reconciliation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("matchbox.reconciliation")
record ReconciliationProperties(Duration settlementWindow, String webhookSecret) {
}
