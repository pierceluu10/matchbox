package dev.matchbox.exceptions;

import tools.jackson.databind.json.JsonMapper;

import dev.matchbox.shared.events.ReconciliationException;
import dev.matchbox.shared.events.Topics;
import dev.matchbox.shared.kafka.NonRetryableEventException;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class ReconciliationExceptionListener {

	private final ExceptionCaseService service;

	private final JsonMapper jsonMapper;

	ReconciliationExceptionListener(ExceptionCaseService service, JsonMapper jsonMapper) {
		this.service = service;
		this.jsonMapper = jsonMapper;
	}

	@KafkaListener(topics = Topics.RECONCILIATION_EXCEPTION)
	void onReconciliationException(String payload) {
		ReconciliationException event = this.jsonMapper.readValue(payload, ReconciliationException.class);
		if (event.paymentId() == null || event.type() == null || event.detectedAt() == null) {
			throw new NonRetryableEventException("Incomplete reconciliation-exception event: " + payload);
		}
		this.service.open(event);
	}

}
