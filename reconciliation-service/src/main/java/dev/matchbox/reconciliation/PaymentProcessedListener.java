package dev.matchbox.reconciliation;

import tools.jackson.databind.json.JsonMapper;

import dev.matchbox.shared.events.PaymentProcessed;
import dev.matchbox.shared.events.Topics;
import dev.matchbox.shared.kafka.NonRetryableEventException;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class PaymentProcessedListener {

	private final ReconciliationService reconciliation;

	private final JsonMapper jsonMapper;

	PaymentProcessedListener(ReconciliationService reconciliation, JsonMapper jsonMapper) {
		this.reconciliation = reconciliation;
		this.jsonMapper = jsonMapper;
	}

	@KafkaListener(topics = Topics.PAYMENT_PROCESSED)
	void onPaymentProcessed(String payload) {
		PaymentProcessed event = this.jsonMapper.readValue(payload, PaymentProcessed.class);
		if (event.paymentId() == null || event.outcome() == null || event.amount() == null
				|| event.currency() == null) {
			throw new NonRetryableEventException("Incomplete payment-processed event: " + payload);
		}
		this.reconciliation.recordPayment(event);
	}

}
