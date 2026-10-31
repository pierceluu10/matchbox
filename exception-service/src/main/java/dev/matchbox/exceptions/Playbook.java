package dev.matchbox.exceptions;

import dev.matchbox.shared.events.ExceptionType;

/** What an operator should do about each kind of exception. */
final class Playbook {

	private Playbook() {
	}

	static String recommendedAction(ExceptionType type) {
		return switch (type) {
			case AMOUNT_MISMATCH -> "Compare the charge with the bank statement and request a settlement correction";
			case MISSING_SETTLEMENT -> "Ask the bank to trace the settlement; refund the customer if it never arrives";
			case UNEXPECTED_SETTLEMENT -> "Confirm with the bank and return funds we did not charge for";
		};
	}

}
