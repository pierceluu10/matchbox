package dev.matchbox.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import dev.matchbox.shared.events.ExceptionStatus;

/** {@code exceptionStatus} is null until reconciliation flags it. */
public record Payment(UUID paymentId, String customerId, BigDecimal amount, String currency, PaymentStatus status,
		ExceptionStatus exceptionStatus, Instant createdAt, Instant processedAt) {
}
