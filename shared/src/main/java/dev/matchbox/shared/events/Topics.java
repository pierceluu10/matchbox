package dev.matchbox.shared.events;

public final class Topics {

	public static final String PAYMENT_REQUESTED = "payment-requested";
	public static final String PAYMENT_PROCESSED = "payment-processed";
	public static final String RECONCILIATION_EXCEPTION = "reconciliation-exception";
	public static final String EXCEPTION_UPDATED = "exception-updated";

	public static final String DEAD_LETTER_SUFFIX = ".dlq";

	public static final int PARTITIONS = 6;

	private Topics() {
	}

}
