package dev.matchbox.payment;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreatePaymentRequest(@NotBlank @Size(max = 64) String customerId,
		@NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
		@NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
}
