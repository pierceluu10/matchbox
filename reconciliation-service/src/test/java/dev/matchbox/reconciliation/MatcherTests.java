package dev.matchbox.reconciliation;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import dev.matchbox.shared.events.ChargeOutcome;
import dev.matchbox.shared.events.ExceptionType;

import static org.assertj.core.api.Assertions.assertThat;

class MatcherTests {

	@Test
	void equalAmountsMatchRegardlessOfScale() {
		assertThat(Matcher.compare(ChargeOutcome.CHARGED, new BigDecimal("10.0"), "USD", new BigDecimal("10.00"),
				"USD"))
			.isEmpty();
	}

	@Test
	void differentAmountIsAMismatch() {
		assertThat(Matcher.compare(ChargeOutcome.CHARGED, new BigDecimal("10.00"), "USD", new BigDecimal("11.00"),
				"USD"))
			.contains(ExceptionType.AMOUNT_MISMATCH);
	}

	@Test
	void differentCurrencyIsAMismatch() {
		assertThat(Matcher.compare(ChargeOutcome.CHARGED, new BigDecimal("10.00"), "USD", new BigDecimal("10.00"),
				"EUR"))
			.contains(ExceptionType.AMOUNT_MISMATCH);
	}

	@Test
	void settlementOfAFailedPaymentIsUnexpected() {
		assertThat(Matcher.compare(ChargeOutcome.FAILED, new BigDecimal("10.00"), "USD", new BigDecimal("10.00"),
				"USD"))
			.contains(ExceptionType.UNEXPECTED_SETTLEMENT);
	}

}
