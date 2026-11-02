package dev.matchbox.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import dev.matchbox.shared.events.ExceptionStatus;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepository {

	private static final String COLUMNS = "id, customer_id, amount, currency, status, exception_status, created_at, processed_at";

	private final JdbcClient jdbc;

	PaymentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Returns empty when the idempotency key was already used, in which case nothing is written. */
	Optional<Payment> insert(UUID paymentId, String idempotencyKey, CreatePaymentRequest request) {
		return this.jdbc.sql("""
				INSERT INTO payments (id, idempotency_key, customer_id, amount, currency, status)
				VALUES (:id, :idempotencyKey, :customerId, :amount, :currency, :status)
				ON CONFLICT (idempotency_key) DO NOTHING
				RETURNING\s""" + COLUMNS)
			.param("id", paymentId)
			.param("idempotencyKey", idempotencyKey)
			.param("customerId", request.customerId())
			.param("amount", request.amount())
			.param("currency", request.currency())
			.param("status", PaymentStatus.PENDING.name())
			.query(PaymentRepository::map)
			.optional();
	}

	Optional<Payment> findById(UUID paymentId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE id = :id")
			.param("id", paymentId)
			.query(PaymentRepository::map)
			.optional();
	}

	Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE idempotency_key = :key")
			.param("key", idempotencyKey)
			.query(PaymentRepository::map)
			.optional();
	}

	/** Only moves a PENDING payment, so a replayed charge result changes nothing and returns empty. */
	Optional<Payment> markProcessed(UUID paymentId, PaymentStatus status) {
		return this.jdbc.sql("UPDATE payments SET status = :status, processed_at = now() "
				+ "WHERE id = :id AND status = 'PENDING' RETURNING " + COLUMNS)
			.param("id", paymentId)
			.param("status", status.name())
			.query(PaymentRepository::map)
			.optional();
	}

	void updateExceptionStatus(UUID paymentId, ExceptionStatus status) {
		this.jdbc.sql("UPDATE payments SET exception_status = :status WHERE id = :id")
			.param("id", paymentId)
			.param("status", status.name())
			.update();
	}

	private static Payment map(ResultSet rs, int row) throws SQLException {
		String exceptionStatus = rs.getString("exception_status");
		OffsetDateTime processedAt = rs.getObject("processed_at", OffsetDateTime.class);
		return new Payment(rs.getObject("id", UUID.class), rs.getString("customer_id"), rs.getBigDecimal("amount"),
				rs.getString("currency"), PaymentStatus.valueOf(rs.getString("status")),
				(exceptionStatus != null) ? ExceptionStatus.valueOf(exceptionStatus) : null,
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				(processedAt != null) ? processedAt.toInstant() : null);
	}

}
