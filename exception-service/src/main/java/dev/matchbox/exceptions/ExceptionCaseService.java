package dev.matchbox.exceptions;

import java.util.UUID;

import dev.matchbox.shared.events.ExceptionUpdated;
import dev.matchbox.shared.events.ReconciliationException;
import dev.matchbox.shared.events.Topics;
import dev.matchbox.shared.outbox.OutboxWriter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExceptionCaseService {

	private final ExceptionRepository repository;

	private final OutboxWriter outbox;

	ExceptionCaseService(ExceptionRepository repository, OutboxWriter outbox) {
		this.repository = repository;
		this.outbox = outbox;
	}

	/**
	 * Opens a case and tells the payment service, once. Events are keyed by paymentId, so copies of
	 * the same exception arrive in order on one partition and the unique (payment_id, type) row
	 * turns every copy after the first into a no-op.
	 */
	@Transactional
	public void open(ReconciliationException event) {
		this.repository.insertIfAbsent(event, Playbook.recommendedAction(event.type())).ifPresent(this::publish);
	}

	@Transactional
	public ExceptionCase resolve(UUID exceptionId, String note) {
		ExceptionCase resolved = this.repository.resolve(exceptionId, note).orElseThrow(() -> this.repository
			.findById(exceptionId)
			.<RuntimeException>map((existing) -> new ExceptionAlreadyResolvedException(exceptionId))
			.orElseGet(() -> new ExceptionNotFoundException(exceptionId)));
		publish(resolved);
		return resolved;
	}

	private void publish(ExceptionCase exceptionCase) {
		this.outbox.append(Topics.EXCEPTION_UPDATED, exceptionCase.paymentId(),
				new ExceptionUpdated(exceptionCase.paymentId(), exceptionCase.exceptionId(), exceptionCase.type(),
						exceptionCase.status(), (exceptionCase.resolvedAt() != null) ? exceptionCase.resolvedAt()
								: exceptionCase.detectedAt()));
	}

}
