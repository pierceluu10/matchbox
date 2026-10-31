package dev.matchbox.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** What the bank's settlement webhook sends. */
record Settlement(UUID paymentId, BigDecimal amount, String currency, Instant settledAt) {

	boolean isComplete() {
		return this.paymentId != null && this.amount != null && this.amount.signum() > 0 && this.currency != null
				&& this.currency.length() == 3 && this.settledAt != null;
	}

}
