package dev.matchbox.payment;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
class PaymentController {

	static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private static final String RETRY_AFTER_SECONDS = "1";

	private final PaymentService payments;

	PaymentController(PaymentService payments) {
		this.payments = payments;
	}

	/** Accepts the payment for asynchronous processing; poll the Location header for the outcome. */
	@PostMapping
	ResponseEntity<PaymentAccepted> create(@Valid @RequestBody CreatePaymentRequest request,
			@RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
		Payment payment = this.payments.create(request, idempotencyKey);
		return ResponseEntity.accepted()
			.location(URI.create("/payments/" + payment.paymentId()))
			.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
			.body(new PaymentAccepted(payment.paymentId(), payment.status()));
	}

	@GetMapping("/{paymentId}")
	ResponseEntity<Payment> get(@PathVariable UUID paymentId) {
		return ResponseEntity.ofNullable(this.payments.find(paymentId));
	}

}
