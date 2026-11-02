package dev.matchbox.shared.events;

public enum ExceptionType {

	/** Bank settled a different amount or currency than we charged. */
	AMOUNT_MISMATCH,
	/** We charged successfully but the bank never settled. */
	MISSING_SETTLEMENT,
	/** Bank settled a payment we failed or never processed. */
	UNEXPECTED_SETTLEMENT

}
