package dev.matchbox.bank;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Injected outage; callers are expected to retry. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
class BankUnavailableException extends RuntimeException {

	BankUnavailableException() {
		super("Bank temporarily unavailable");
	}

}
