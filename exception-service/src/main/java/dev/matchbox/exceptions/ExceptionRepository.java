package dev.matchbox.exceptions;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.matchbox.shared.events.ExceptionStatus;
import dev.matchbox.shared.events.ExceptionType;
import dev.matchbox.shared.events.ReconciliationException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ExceptionRepository {

	private static final String COLUMNS = "id, payment_id, type, status, expected_amount, settled_amount, "
			+ "recommended_action, resolution_note, detected_at, resolved_at";

	private final JdbcClient jdbc;

	ExceptionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Empty when this payment already has an exception of this type. */
	Optional<ExceptionCase> insertIfAbsent(ReconciliationException event, String recommendedAction) {
		return this.jdbc.sql("INSERT INTO exceptions (id, payment_id, type, status, expected_amount, settled_amount, "
				+ "recommended_action, detected_at) VALUES (:id, :paymentId, :type, 'OPEN', :expected, :settled, "
				+ ":action, :detectedAt) ON CONFLICT (payment_id, type) DO NOTHING RETURNING " + COLUMNS)
			.param("id", UUID.randomUUID())
			.param("paymentId", event.paymentId())
			.param("type", event.type().name())
			.param("expected", event.expectedAmount())
			.param("settled", event.settledAmount())
			.param("action", recommendedAction)
			.param("detectedAt", OffsetDateTime.ofInstant(event.detectedAt(), ZoneOffset.UTC))
			.query(ExceptionRepository::map)
			.optional();
	}

	Optional<ExceptionCase> findById(UUID exceptionId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM exceptions WHERE id = :id")
			.param("id", exceptionId)
			.query(ExceptionRepository::map)
			.optional();
	}

	/** Null filters match everything. */
	List<ExceptionCase> find(ExceptionStatus status, ExceptionType type, int limit) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM exceptions "
				+ "WHERE (CAST(:status AS TEXT) IS NULL OR status = :status) "
				+ "AND (CAST(:type AS TEXT) IS NULL OR type = :type) ORDER BY detected_at DESC LIMIT :limit")
			.param("status", (status != null) ? status.name() : null)
			.param("type", (type != null) ? type.name() : null)
			.param("limit", limit)
			.query(ExceptionRepository::map)
			.list();
	}

	List<ExceptionSummary> summarize() {
		return this.jdbc.sql("SELECT type, status, count(*) AS count FROM exceptions GROUP BY type, status ORDER BY type, status")
			.query((rs, row) -> new ExceptionSummary(ExceptionType.valueOf(rs.getString("type")),
					ExceptionStatus.valueOf(rs.getString("status")), rs.getLong("count")))
			.list();
	}

	/** Empty unless the exception exists and was still open. */
	Optional<ExceptionCase> resolve(UUID exceptionId, String note) {
		return this.jdbc.sql("UPDATE exceptions SET status = 'RESOLVED', resolution_note = :note, resolved_at = now() "
				+ "WHERE id = :id AND status = 'OPEN' RETURNING " + COLUMNS)
			.param("id", exceptionId)
			.param("note", note)
			.query(ExceptionRepository::map)
			.optional();
	}

	private static ExceptionCase map(ResultSet rs, int row) throws SQLException {
		OffsetDateTime resolvedAt = rs.getObject("resolved_at", OffsetDateTime.class);
		return new ExceptionCase(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
				ExceptionType.valueOf(rs.getString("type")), ExceptionStatus.valueOf(rs.getString("status")),
				rs.getBigDecimal("expected_amount"), rs.getBigDecimal("settled_amount"),
				rs.getString("recommended_action"), rs.getString("resolution_note"),
				rs.getObject("detected_at", OffsetDateTime.class).toInstant(),
				(resolvedAt != null) ? resolvedAt.toInstant() : null);
	}

}
