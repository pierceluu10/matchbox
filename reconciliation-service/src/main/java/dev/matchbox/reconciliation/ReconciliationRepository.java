package dev.matchbox.reconciliation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import dev.matchbox.shared.events.ChargeOutcome;
import dev.matchbox.shared.events.ExceptionType;
import dev.matchbox.shared.events.PaymentProcessed;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ReconciliationRepository {

	private static final int SWEEP_BATCH_SIZE = 1_000;

	private final JdbcClient jdbc;

	ReconciliationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Creates the row if needed and locks it, so the Kafka consumer and the webhook can never
	 * evaluate the same payment at the same time.
	 */
	ReconciliationRecord lock(UUID paymentId) {
		this.jdbc.sql("INSERT INTO reconciliation_records (payment_id) VALUES (:id) ON CONFLICT DO NOTHING")
			.param("id", paymentId)
			.update();
		return this.jdbc.sql("""
				SELECT payment_id, state, our_outcome, our_amount, our_currency, bank_amount, bank_currency
				FROM reconciliation_records WHERE payment_id = :id FOR UPDATE
				""").param("id", paymentId).query(ReconciliationRepository::map).single();
	}

	void recordOurSide(PaymentProcessed event) {
		this.jdbc.sql("""
				UPDATE reconciliation_records
				SET our_outcome = :outcome, our_amount = :amount, our_currency = :currency, our_received_at = now()
				WHERE payment_id = :id
				""")
			.param("id", event.paymentId())
			.param("outcome", event.outcome().name())
			.param("amount", event.amount())
			.param("currency", event.currency())
			.update();
	}

	void recordBankSide(Settlement settlement) {
		this.jdbc.sql("""
				UPDATE reconciliation_records
				SET bank_amount = :amount, bank_currency = :currency, bank_settled_at = :settledAt,
				    bank_received_at = now()
				WHERE payment_id = :id
				""")
			.param("id", settlement.paymentId())
			.param("amount", settlement.amount())
			.param("currency", settlement.currency())
			.param("settledAt", OffsetDateTime.ofInstant(settlement.settledAt(), ZoneOffset.UTC))
			.update();
	}

	void close(UUID paymentId, RecordState state, ExceptionType exceptionType) {
		this.jdbc.sql("UPDATE reconciliation_records SET state = :state, exception_type = :type WHERE payment_id = :id")
			.param("id", paymentId)
			.param("state", state.name())
			.param("type", (exceptionType != null) ? exceptionType.name() : null)
			.update();
	}

	/** Charged payments the bank never settled within the window. */
	List<Expired> expireMissingSettlements(Duration window) {
		return expire(ExceptionType.MISSING_SETTLEMENT, "our_amount",
				"our_outcome = 'CHARGED' AND bank_amount IS NULL AND our_received_at < now() - make_interval(secs => :seconds)",
				window);
	}

	/** Settlements for payments we never processed within the window. */
	List<Expired> expireUnexpectedSettlements(Duration window) {
		return expire(ExceptionType.UNEXPECTED_SETTLEMENT, "bank_amount",
				"our_outcome IS NULL AND bank_amount IS NOT NULL AND bank_received_at < now() - make_interval(secs => :seconds)",
				window);
	}

	/** Failed payments correctly left unsettled: nothing more will arrive, so they reconcile cleanly. */
	int closeUnsettledFailures(Duration window) {
		return this.jdbc.sql("""
				UPDATE reconciliation_records SET state = 'MATCHED'
				WHERE payment_id IN (
				    SELECT payment_id FROM reconciliation_records
				    WHERE state = 'PENDING' AND our_outcome = 'FAILED' AND bank_amount IS NULL
				      AND our_received_at < now() - make_interval(secs => :seconds)
				    LIMIT :limit FOR UPDATE SKIP LOCKED)
				""").param("seconds", window.toSeconds()).param("limit", SWEEP_BATCH_SIZE).update();
	}

	// The condition fragments are constants above, never user input.
	private List<Expired> expire(ExceptionType type, String amountColumn, String condition, Duration window) {
		return this.jdbc.sql("UPDATE reconciliation_records SET state = 'EXCEPTION', exception_type = :type "
				+ "WHERE payment_id IN (SELECT payment_id FROM reconciliation_records WHERE state = 'PENDING' AND "
				+ condition + " LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING payment_id, " + amountColumn)
			.param("type", type.name())
			.param("seconds", window.toSeconds())
			.param("limit", SWEEP_BATCH_SIZE)
			.query((rs, row) -> new Expired(rs.getObject(1, UUID.class), rs.getBigDecimal(2)))
			.list();
	}

	private static ReconciliationRecord map(ResultSet rs, int row) throws SQLException {
		String outcome = rs.getString("our_outcome");
		return new ReconciliationRecord(rs.getObject("payment_id", UUID.class),
				RecordState.valueOf(rs.getString("state")), (outcome != null) ? ChargeOutcome.valueOf(outcome) : null,
				rs.getBigDecimal("our_amount"), rs.getString("our_currency"), rs.getBigDecimal("bank_amount"),
				rs.getString("bank_currency"));
	}

	record Expired(UUID paymentId, BigDecimal amount) {
	}

}
