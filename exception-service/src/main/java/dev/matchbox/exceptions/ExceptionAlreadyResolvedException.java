package dev.matchbox.exceptions;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
class ExceptionAlreadyResolvedException extends RuntimeException {

	ExceptionAlreadyResolvedException(UUID exceptionId) {
		super("Exception " + exceptionId + " is already resolved");
	}

}
