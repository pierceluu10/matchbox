package dev.matchbox.shared.events;

import java.time.Instant;
import java.util.UUID;

public record ExceptionUpdated(UUID paymentId, UUID exceptionId, ExceptionType type, ExceptionStatus status,
		Instant updatedAt) {
}
