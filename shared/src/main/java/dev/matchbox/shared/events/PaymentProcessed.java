package dev.matchbox.shared.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentProcessed(UUID paymentId, ChargeOutcome outcome, BigDecimal amount, String currency, Instant processedAt) {
}
