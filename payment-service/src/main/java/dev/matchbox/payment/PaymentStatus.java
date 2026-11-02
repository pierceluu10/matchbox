package dev.matchbox.payment;

public enum PaymentStatus {

	PENDING, CHARGED, FAILED;

	boolean isTerminal() {
		return this != PENDING;
	}

}
