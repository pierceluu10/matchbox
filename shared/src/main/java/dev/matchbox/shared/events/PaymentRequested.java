package dev.matchbox.shared.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentRequested(UUID paymentId, String customerId, BigDecimal amount, String currency, Instant requestedAt) {
}
