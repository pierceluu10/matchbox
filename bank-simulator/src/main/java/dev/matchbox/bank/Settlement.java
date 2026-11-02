package dev.matchbox.bank;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record Settlement(UUID paymentId, BigDecimal amount, String currency, Instant settledAt) {
}
