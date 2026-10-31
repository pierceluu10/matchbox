package dev.matchbox.exceptions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import dev.matchbox.shared.events.ExceptionStatus;
import dev.matchbox.shared.events.ExceptionType;

public record ExceptionCase(UUID exceptionId, UUID paymentId, ExceptionType type, ExceptionStatus status,
		BigDecimal expectedAmount, BigDecimal settledAmount, String recommendedAction, String resolutionNote,
		Instant detectedAt, Instant resolvedAt) {
}
