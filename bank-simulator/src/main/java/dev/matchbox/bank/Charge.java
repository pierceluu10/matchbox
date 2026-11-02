package dev.matchbox.bank;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

final class Charge {

	private Charge() {
	}

	enum Decision {

		APPROVED, DECLINED

	}

	record Request(@NotNull UUID paymentId, @NotNull @Positive BigDecimal amount,
			@NotNull @Size(min = 3, max = 3) String currency) {
	}

	record Response(UUID paymentId, Decision decision) {
	}

}
