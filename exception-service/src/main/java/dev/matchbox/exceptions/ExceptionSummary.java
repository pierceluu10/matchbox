package dev.matchbox.exceptions;

import dev.matchbox.shared.events.ExceptionStatus;
import dev.matchbox.shared.events.ExceptionType;

public record ExceptionSummary(ExceptionType type, ExceptionStatus status, long count) {
}
