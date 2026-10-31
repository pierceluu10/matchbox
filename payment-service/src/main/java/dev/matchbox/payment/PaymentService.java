package dev.matchbox.payment;

import java.time.Duration;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import dev.matchbox.shared.events.ChargeOutcome;
import dev.matchbox.shared.events.ExceptionUpdated;
import dev.matchbox.shared.events.PaymentProcessed;
import dev.matchbox.shared.events.PaymentRequested;
import dev.matchbox.shared.events.Topics;
import dev.matchbox.shared.outbox.OutboxWriter;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentService {

	static final String CACHE = "payments";

	private static final double P95 = 0.95;

	private final PaymentRepository repository;

	private final OutboxWriter outbox;

	private final Timer processingTimer;

	PaymentService(PaymentRepository repository, OutboxWriter outbox, MeterRegistry meterRegistry) {
		this.repository = repository;
		this.outbox = outbox;
		this.processingTimer = Timer.builder("matchbox.payment.processing")
			.description("Time from payment accepted to charge outcome recorded")
			.publishPercentiles(P95)
			.publishPercentileHistogram()
			.register(meterRegistry);
	}

	/**
	 * Inserts the PENDING row and its outbox event in one transaction. Reusing an idempotency key
	 * returns the original payment instead of creating a second one.
	 */
	@Transactional
	Payment create(CreatePaymentRequest request, String idempotencyKey) {
		UUID paymentId = UUID.randomUUID();
		return this.repository.insert(paymentId, idempotencyKey, request).map((payment) -> {
			this.outbox.append(Topics.PAYMENT_REQUESTED, paymentId, new PaymentRequested(paymentId,
					payment.customerId(), payment.amount(), payment.currency(), payment.createdAt()));
			return payment;
		}).orElseGet(() -> existing(idempotencyKey, request));
	}

	private Payment existing(String idempotencyKey, CreatePaymentRequest request) {
		Payment payment = this.repository.findByIdempotencyKey(idempotencyKey).orElseThrow();
		boolean samePayment = payment.customerId().equals(request.customerId())
				&& payment.amount().compareTo(request.amount()) == 0 && payment.currency().equals(request.currency());
		if (!samePayment) {
			throw new IdempotencyKeyConflictException(idempotencyKey);
		}
		return payment;
	}

	@Cacheable(cacheNames = CACHE, unless = "#result == null")
	public Payment find(UUID paymentId) {
		return this.repository.findById(paymentId).orElse(null);
	}

	@Transactional
	@CacheEvict(cacheNames = CACHE, key = "#paymentId")
	public void recordCharge(UUID paymentId, PaymentStatus status) {
		this.repository.markProcessed(paymentId, status).ifPresent((payment) -> {
			ChargeOutcome outcome = (status == PaymentStatus.CHARGED) ? ChargeOutcome.CHARGED : ChargeOutcome.FAILED;
			this.outbox.append(Topics.PAYMENT_PROCESSED, paymentId, new PaymentProcessed(paymentId, outcome,
					payment.amount(), payment.currency(), payment.processedAt()));
			this.processingTimer.record(Duration.between(payment.createdAt(), payment.processedAt()));
		});
	}

	@Transactional
	@CacheEvict(cacheNames = CACHE, key = "#event.paymentId()")
	public void applyExceptionUpdate(ExceptionUpdated event) {
		this.repository.updateExceptionStatus(event.paymentId(), event.status());
	}

}
