package dev.matchbox.shared;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

/** Real Kafka and Postgres for integration tests; images match docker-compose.yml. */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaPostgresContainers {

	public static final String KAFKA_IMAGE = "apache/kafka:4.1.0";

	public static final String POSTGRES_IMAGE = "postgres:17-alpine";

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(KAFKA_IMAGE);
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGRES_IMAGE);
	}

}
