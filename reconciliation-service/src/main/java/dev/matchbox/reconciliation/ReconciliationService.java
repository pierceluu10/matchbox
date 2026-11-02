package dev.matchbox.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;

import dev.matchbox.shared.events.ExceptionType;
import dev.matchbox.shared.events.PaymentProcessed;
import dev.matchbox.shared.events.ReconciliationException;
import dev.matchbox.shared.events.Topics;
import dev.matchbox.shared.outbox.OutboxWriter;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whichever side arrives second triggers the comparison. Each side is recorded at most once, so
 * replayed events and duplicate webhooks change nothing.
 */
@Service
class ReconciliationService {

	private static final String OUTCOME_METRIC = "matchbox.reconciliation.outcomes";

	private static final String MATCHED = "matched";

	private final ReconciliationRepository repository;

	private final OutboxWriter outbox;

	private final ReconciliationProperties properties;

	private final MeterRegistry meterRegistry;

	ReconciliationService(ReconciliationRepository repository, OutboxWriter outbox,
			ReconciliationProperties properties, MeterRegistry meterRegistry) {
		this.repository = repository;
		this.outbox = outbox;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
	}

	@Transactional
	public void recordPayment(PaymentProcessed event) {
		ReconciliationRecord record = this.repository.lock(event.paymentId());
		if (record.hasOurSide()) {
			return;
		}
		this.repository.recordOurSide(event);
		compareIfComplete(new ReconciliationRecord(record.paymentId(), record.state(), event.outcome(),
				event.amount(), event.currency(), record.bankAmount(), record.bankCurrency()));
	}

	@Transactional
	public void recordSettlement(Settlement settlement) {
		ReconciliationRecord record = this.repository.lock(settlement.paymentId());
		if (record.hasBankSide()) {
			return;
		}
		this.repository.recordBankSide(settlement);
		compareIfComplete(new ReconciliationRecord(record.paymentId(), record.state(), record.ourOutcome(),
				record.ourAmount(), record.ourCurrency(), settlement.amount(), settlement.currency()));
	}

	/**
	 * Raises exceptions for sides that waited too long. A side arriving after its record was
	 * already flagged is stored but not re-compared; the open exception covers it.
	 */
	@Scheduled(fixedDelayString = "${matchbox.reconciliation.sweep-interval-ms}")
	@Transactional
	public void sweepExpired() {
		this.repository.expireMissingSettlements(this.properties.settlementWindow())
			.forEach((expired) -> raise(expired.paymentId(), ExceptionType.MISSING_SETTLEMENT, expired.amount(), null));
		this.repository.expireUnexpectedSettlements(this.properties.settlementWindow())
			.forEach((expired) -> raise(expired.paymentId(), ExceptionType.UNEXPECTED_SETTLEMENT, null,
					expired.amount()));
		this.meterRegistry.counter(OUTCOME_METRIC, "outcome", MATCHED)
			.increment(this.repository.closeUnsettledFailures(this.properties.settlementWindow()));
	}

	private void compareIfComplete(ReconciliationRecord record) {
		if (record.state() != RecordState.PENDING || !record.hasOurSide() || !record.hasBankSide()) {
			return;
		}
		Optional<ExceptionType> mismatch = Matcher.compare(record.ourOutcome(), record.ourAmount(),
				record.ourCurrency(), record.bankAmount(), record.bankCurrency());
		if (mismatch.isEmpty()) {
			this.repository.close(record.paymentId(), RecordState.MATCHED, null);
			this.meterRegistry.counter(OUTCOME_METRIC, "outcome", MATCHED).increment();
			return;
		}
		this.repository.close(record.paymentId(), RecordState.EXCEPTION, mismatch.get());
		raise(record.paymentId(), mismatch.get(), record.ourAmount(), record.bankAmount());
	}

	private void raise(UUID paymentId, ExceptionType type, BigDecimal expected, BigDecimal settled) {
		this.outbox.append(Topics.RECONCILIATION_EXCEPTION, paymentId,
				new ReconciliationException(paymentId, type, expected, settled, Instant.now()));
		this.meterRegistry.counter(OUTCOME_METRIC, "outcome", type.name().toLowerCase()).increment();
	}

}
