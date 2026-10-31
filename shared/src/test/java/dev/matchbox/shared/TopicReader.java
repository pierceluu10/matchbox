package dev.matchbox.shared;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import org.springframework.kafka.core.ConsumerFactory;

import static org.awaitility.Awaitility.await;

/** Reads a topic from the beginning with a throwaway consumer group, for asserting on published events. */
public final class TopicReader {

	private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);

	private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);

	private TopicReader() {
	}

	public static List<ConsumerRecord<String, String>> awaitRecords(ConsumerFactory<String, String> consumerFactory,
			String topic, String key) {
		Properties overrides = new Properties();
		overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		List<ConsumerRecord<String, String>> found = new ArrayList<>();
		try (Consumer<String, String> consumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), null,
				null, overrides)) {
			consumer.subscribe(List.of(topic));
			await().atMost(WAIT_TIMEOUT).until(() -> {
				consumer.poll(POLL_TIMEOUT).forEach((record) -> {
					if (key.equals(record.key())) {
						found.add(record);
					}
				});
				return !found.isEmpty();
			});
		}
		return found;
	}

}
