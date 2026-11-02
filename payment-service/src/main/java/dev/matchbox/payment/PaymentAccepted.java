package dev.matchbox.payment;

import java.util.UUID;

public record PaymentAccepted(UUID paymentId, PaymentStatus status) {
}
