package dev.matchbox.payment;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.UUID;

import dev.matchbox.shared.kafka.NonRetryableEventException;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Calls the bank. Server errors and timeouts propagate so the Kafka error handler retries them; a
 * 4xx means the request itself is wrong, so retrying cannot help.
 */
@Component
class BankClient {

	private final RestClient rest;

	BankClient(BankProperties properties) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
		requestFactory.setReadTimeout(properties.readTimeout());
		this.rest = RestClient.builder().baseUrl(properties.url().toString()).requestFactory(requestFactory).build();
	}

	/** The bank deduplicates on paymentId, so calling this again after a crash never double-charges. */
	ChargeDecision charge(UUID paymentId, BigDecimal amount, String currency) {
		try {
			return this.rest.post()
				.uri("/charges")
				.body(new ChargeRequest(paymentId, amount, currency))
				.retrieve()
				.body(ChargeResponse.class)
				.decision();
		}
		catch (HttpClientErrorException ex) {
			throw new NonRetryableEventException("Bank rejected charge request for payment " + paymentId, ex);
		}
	}

	enum ChargeDecision {

		APPROVED, DECLINED

	}

	record ChargeRequest(UUID paymentId, BigDecimal amount, String currency) {
	}

	record ChargeResponse(UUID paymentId, ChargeDecision decision) {
	}

}
