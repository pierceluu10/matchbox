package dev.matchbox.reconciliation;

import java.math.BigDecimal;
import java.util.Optional;

import dev.matchbox.shared.events.ChargeOutcome;
import dev.matchbox.shared.events.ExceptionType;

/** Pure comparison of both sides; empty means they agree. */
final class Matcher {

	private Matcher() {
	}

	static Optional<ExceptionType> compare(ChargeOutcome ourOutcome, BigDecimal ourAmount, String ourCurrency,
			BigDecimal bankAmount, String bankCurrency) {
		if (ourOutcome == ChargeOutcome.FAILED) {
			return Optional.of(ExceptionType.UNEXPECTED_SETTLEMENT);
		}
		if (ourAmount.compareTo(bankAmount) != 0 || !ourCurrency.equals(bankCurrency)) {
			return Optional.of(ExceptionType.AMOUNT_MISMATCH);
		}
		return Optional.empty();
	}

}
