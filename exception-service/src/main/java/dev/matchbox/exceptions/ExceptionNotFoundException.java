package dev.matchbox.exceptions;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class ExceptionNotFoundException extends RuntimeException {

	ExceptionNotFoundException(UUID exceptionId) {
		super("No exception " + exceptionId);
	}

}
