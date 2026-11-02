package dev.matchbox.exceptions;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import dev.matchbox.shared.events.ExceptionStatus;
import dev.matchbox.shared.events.ExceptionType;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/exceptions")
class ExceptionController {

	private static final int MAX_PAGE = 500;

	private final ExceptionRepository repository;

	private final ExceptionCaseService service;

	ExceptionController(ExceptionRepository repository, ExceptionCaseService service) {
		this.repository = repository;
		this.service = service;
	}

	@GetMapping
	List<ExceptionCase> list(@RequestParam(required = false) ExceptionStatus status,
			@RequestParam(required = false) ExceptionType type,
			@RequestParam(defaultValue = "100") @Min(1) @Max(MAX_PAGE) int limit) {
		return this.repository.find(status, type, limit);
	}

	@GetMapping("/summary")
	List<ExceptionSummary> summary() {
		return this.repository.summarize();
	}

	@GetMapping("/{exceptionId}")
	ExceptionCase get(@PathVariable UUID exceptionId) {
		return this.repository.findById(exceptionId).orElseThrow(() -> new ExceptionNotFoundException(exceptionId));
	}

	@PostMapping("/{exceptionId}/resolve")
	ExceptionCase resolve(@PathVariable UUID exceptionId, @Valid @RequestBody Resolution resolution) {
		return this.service.resolve(exceptionId, resolution.note());
	}

	record Resolution(@NotBlank @Size(max = 500) String note) {
	}

}
