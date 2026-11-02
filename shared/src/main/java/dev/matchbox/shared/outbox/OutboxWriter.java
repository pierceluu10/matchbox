package dev.matchbox.shared.outbox;

import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an event in the same database transaction as the state change that caused it, so the
 * two can never disagree. {@link OutboxRelay} publishes it to Kafka afterwards.
 */
@Component
public class OutboxWriter {

	private final JdbcClient jdbc;

	private final JsonMapper jsonMapper;

	OutboxWriter(JdbcClient jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void append(String topic, UUID key, Object event) {
		this.jdbc.sql("INSERT INTO outbox (topic, message_key, payload) VALUES (:topic, :key, :payload)")
			.param("topic", topic)
			.param("key", key.toString())
			.param("payload", this.jsonMapper.writeValueAsString(event))
			.update();
	}

}
