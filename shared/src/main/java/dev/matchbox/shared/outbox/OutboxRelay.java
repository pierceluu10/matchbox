package dev.matchbox.shared.outbox;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes outbox rows in id order and deletes them once Kafka acknowledges. A crash between the
 * acknowledgement and the delete republishes the batch, so delivery is at-least-once and every
 * consumer must be idempotent.
 */
@Component
public class OutboxRelay {

	private static final int BATCH_SIZE = 500;

	private final JdbcClient jdbc;

	private final KafkaTemplate<String, String> kafka;

	OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka) {
		this.jdbc = jdbc;
		this.kafka = kafka;
	}

	// shortcut: SKIP LOCKED lets several instances share the table, but per-key order is only
	// guaranteed with one relay per service; partition the outbox by key before scaling out.
	@Scheduled(fixedDelayString = "${matchbox.outbox.poll-interval-ms:20}")
	@Transactional
	public void publishPending() {
		List<OutboxMessage> messages = this.jdbc
			.sql("SELECT id, topic, message_key, payload FROM outbox ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED")
			.param("limit", BATCH_SIZE)
			.query(OutboxMessage.class)
			.list();
		if (messages.isEmpty()) {
			return;
		}
		messages.stream()
			.map((message) -> this.kafka.send(message.topic(), message.messageKey(), message.payload()))
			.toList()
			.forEach(CompletableFuture::join);
		this.jdbc.sql("DELETE FROM outbox WHERE id IN (:ids)")
			.param("ids", messages.stream().map(OutboxMessage::id).toList())
			.update();
	}

	record OutboxMessage(long id, String topic, String messageKey, String payload) {
	}

}
