package dev.matchbox.bank;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BankController {

	private final Bank bank;

	private final BankStats stats;

	BankController(Bank bank, BankStats stats) {
		this.bank = bank;
		this.stats = stats;
	}

	@PostMapping("/charges")
	Charge.Response charge(@Valid @RequestBody Charge.Request request) {
		return this.bank.charge(request);
	}

	@GetMapping("/stats")
	BankStats.Snapshot stats() {
		return this.stats.snapshot();
	}

}
