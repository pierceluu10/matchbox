package dev.matchbox.shared.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Either amount may be null when that side of the reconciliation never arrived. */
public record ReconciliationException(UUID paymentId, ExceptionType type, BigDecimal expectedAmount,
		BigDecimal settledAmount, Instant detectedAt) {
}
