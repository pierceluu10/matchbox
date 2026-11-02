package dev.matchbox.shared.kafka;

import java.util.stream.Stream;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import tools.jackson.core.JacksonException;

import dev.matchbox.shared.events.Topics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class KafkaConfiguration {

	private static final int MAX_RETRIES = 4;

	private static final long INITIAL_BACKOFF_MS = 200;

	private static final long MAX_BACKOFF_MS = 2_000;

	private static final double BACKOFF_MULTIPLIER = 2.0;

	// shortcut: replication factor 1 suits the single local broker; use 3 on a real cluster.
	private static final int REPLICAS = 1;

	@Bean
	KafkaAdmin.NewTopics matchboxTopics() {
		return new KafkaAdmin.NewTopics(Stream
			.of(Topics.PAYMENT_REQUESTED, Topics.PAYMENT_PROCESSED, Topics.RECONCILIATION_EXCEPTION,
					Topics.EXCEPTION_UPDATED)
			.flatMap((topic) -> Stream.of(topic, topic + Topics.DEAD_LETTER_SUFFIX))
			.map((topic) -> TopicBuilder.name(topic).partitions(Topics.PARTITIONS).replicas(REPLICAS).build())
			.toArray(NewTopic[]::new));
	}

	/**
	 * Retries transient failures with exponential backoff, then parks the record on
	 * {@code <topic>.dlq} (same partition) so the partition keeps moving. Malformed payloads and
	 * invalid events skip the retries: they would fail identically every time.
	 */
	@Bean
	DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
				(record, exception) -> new TopicPartition(record.topic() + Topics.DEAD_LETTER_SUFFIX,
						record.partition()));
		ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
		backOff.setInitialInterval(INITIAL_BACKOFF_MS);
		backOff.setMultiplier(BACKOFF_MULTIPLIER);
		backOff.setMaxInterval(MAX_BACKOFF_MS);
		DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
		handler.addNotRetryableExceptions(JacksonException.class, NonRetryableEventException.class);
		return handler;
	}

}
