package dev.matchbox.payment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
class IdempotencyKeyConflictException extends RuntimeException {

	IdempotencyKeyConflictException(String idempotencyKey) {
		super("Idempotency key '" + idempotencyKey + "' was already used for a different payment");
	}

}
