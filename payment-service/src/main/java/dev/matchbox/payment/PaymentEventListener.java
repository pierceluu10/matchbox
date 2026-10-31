package dev.matchbox.payment;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import dev.matchbox.shared.events.ExceptionUpdated;
import dev.matchbox.shared.events.PaymentRequested;
import dev.matchbox.shared.events.Topics;
import dev.matchbox.shared.kafka.NonRetryableEventException;

import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;

@Component
class PaymentEventListener {

	private final PaymentService payments;

	private final PaymentRepository repository;

	private final BankClient bank;

	private final JsonMapper jsonMapper;

	PaymentEventListener(PaymentService payments, PaymentRepository repository, BankClient bank,
			JsonMapper jsonMapper) {
		this.payments = payments;
		this.repository = repository;
		this.bank = bank;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Replays of an already-processed payment are skipped before the bank is called. A failed bank
	 * call moves the event to a retry topic instead of retrying in place, so one flaky charge
	 * doesn't hold up every payment behind it on the partition.
	 */
	@RetryableTopic(attempts = "5", backOff = @BackOff(delay = 200, multiplier = 2, maxDelay = 2_000),
			numPartitions = "" + Topics.PARTITIONS, dltTopicSuffix = Topics.DEAD_LETTER_SUFFIX,
			exclude = { JacksonException.class, NonRetryableEventException.class }, traversingCauses = "true")
	@KafkaListener(topics = Topics.PAYMENT_REQUESTED)
	void onPaymentRequested(String payload) {
		PaymentRequested event = this.jsonMapper.readValue(payload, PaymentRequested.class);
		Payment payment = this.repository.findById(event.paymentId())
			.orElseThrow(() -> new NonRetryableEventException("Unknown payment " + event.paymentId()));
		if (payment.status().isTerminal()) {
			return;
		}
		BankClient.ChargeDecision decision = this.bank.charge(payment.paymentId(), payment.amount(),
				payment.currency());
		this.payments.recordCharge(payment.paymentId(),
				(decision == BankClient.ChargeDecision.APPROVED) ? PaymentStatus.CHARGED : PaymentStatus.FAILED);
	}

	@KafkaListener(topics = Topics.EXCEPTION_UPDATED)
	void onExceptionUpdated(String payload) {
		this.payments.applyExceptionUpdate(this.jsonMapper.readValue(payload, ExceptionUpdated.class));
	}

}
