package dev.matchbox.bank;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

@Service
class Bank {

	// shortcut: charges live in memory and are lost on restart, which is fine for a simulator;
	// a real bank would persist them.
	private final Map<UUID, Charge.Response> charges = new ConcurrentHashMap<>();

	private final BankProperties properties;

	private final SettlementSender settlements;

	private final BankStats stats;

	Bank(BankProperties properties, SettlementSender settlements, BankStats stats) {
		this.properties = properties;
		this.settlements = settlements;
		this.stats = stats;
	}

	/**
	 * Idempotent on paymentId: a retried charge returns the original decision and is never
	 * settled twice.
	 */
	Charge.Response charge(Charge.Request request) {
		Charge.Response existing = this.charges.get(request.paymentId());
		if (existing != null) {
			this.stats.duplicateChargeAttempts.increment();
			return existing;
		}
		if (roll(this.properties.transientFailureRate())) {
			this.stats.transientFailures.increment();
			throw new BankUnavailableException();
		}
		Charge.Decision decision = roll(this.properties.declineRate()) ? Charge.Decision.DECLINED
				: Charge.Decision.APPROVED;
		Charge.Response response = new Charge.Response(request.paymentId(), decision);
		Charge.Response winner = this.charges.putIfAbsent(request.paymentId(), response);
		if (winner != null) {
			this.stats.duplicateChargeAttempts.increment();
			return winner;
		}
		if (decision == Charge.Decision.APPROVED) {
			this.stats.approved.increment();
			this.settlements.scheduleFor(request);
		}
		else {
			this.stats.declined.increment();
		}
		return response;
	}

	private static boolean roll(double probability) {
		return ThreadLocalRandom.current().nextDouble() < probability;
	}

}
