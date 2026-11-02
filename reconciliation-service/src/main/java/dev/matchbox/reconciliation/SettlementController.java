package dev.matchbox.reconciliation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bank's settlement webhook. It stores the settlement and compares in the same transaction,
 * then replies; a 200 means the settlement is durable, so the bank can stop retrying.
 */
@RestController
class SettlementController {

	private final WebhookVerifier verifier;

	private final JsonMapper jsonMapper;

	private final ReconciliationService reconciliation;

	SettlementController(WebhookVerifier verifier, JsonMapper jsonMapper, ReconciliationService reconciliation) {
		this.verifier = verifier;
		this.jsonMapper = jsonMapper;
		this.reconciliation = reconciliation;
	}

	@PostMapping("/settlements")
	ResponseEntity<Void> receive(@RequestBody String body,
			@RequestHeader(name = WebhookVerifier.SIGNATURE_HEADER, required = false) String signature) {
		if (!this.verifier.isValid(body, signature)) {
			throw new InvalidSignatureException();
		}
		this.reconciliation.recordSettlement(parse(body));
		return ResponseEntity.ok().build();
	}

	private Settlement parse(String body) {
		try {
			Settlement settlement = this.jsonMapper.readValue(body, Settlement.class);
			if (!settlement.isComplete()) {
				throw new InvalidSettlementException("Settlement is missing required fields");
			}
			return settlement;
		}
		catch (JacksonException ex) {
			throw new InvalidSettlementException("Settlement is not valid JSON");
		}
	}

	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	static class InvalidSignatureException extends RuntimeException {

		InvalidSignatureException() {
			super("Missing or invalid bank signature");
		}

	}

	@ResponseStatus(HttpStatus.BAD_REQUEST)
	static class InvalidSettlementException extends RuntimeException {

		InvalidSettlementException(String message) {
			super(message);
		}

	}

}
