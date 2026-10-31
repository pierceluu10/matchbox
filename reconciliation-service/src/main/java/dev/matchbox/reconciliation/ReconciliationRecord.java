package dev.matchbox.reconciliation;

import java.math.BigDecimal;
import java.util.UUID;

import dev.matchbox.shared.events.ChargeOutcome;

/** Our side or the bank's side may still be null while waiting for it to arrive. */
record ReconciliationRecord(UUID paymentId, RecordState state, ChargeOutcome ourOutcome, BigDecimal ourAmount,
		String ourCurrency, BigDecimal bankAmount, String bankCurrency) {

	boolean hasOurSide() {
		return this.ourOutcome != null;
	}

	boolean hasBankSide() {
		return this.bankAmount != null;
	}

}
