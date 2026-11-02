package dev.matchbox.shared.kafka;

/** An event that will fail the same way on every attempt, so it goes straight to the dead-letter topic. */
public class NonRetryableEventException extends RuntimeException {

	public NonRetryableEventException(String message) {
		super(message);
	}

	public NonRetryableEventException(String message, Throwable cause) {
		super(message, cause);
	}

}
