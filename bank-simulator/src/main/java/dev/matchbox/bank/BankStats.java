package dev.matchbox.bank;

import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

/** Counters the replay test compares before and after a replay to prove nothing was charged twice. */
@Component
class BankStats {

	final LongAdder approved = new LongAdder();

	final LongAdder declined = new LongAdder();

	final LongAdder transientFailures = new LongAdder();

	final LongAdder duplicateChargeAttempts = new LongAdder();

	final LongAdder settlementsDelivered = new LongAdder();

	final LongAdder settlementsDropped = new LongAdder();

	final LongAdder amountsMismatched = new LongAdder();

	final LongAdder duplicateSettlementsSent = new LongAdder();

	final LongAdder deliveryFailures = new LongAdder();

	Snapshot snapshot() {
		return new Snapshot(this.approved.sum(), this.declined.sum(), this.transientFailures.sum(),
				this.duplicateChargeAttempts.sum(), this.settlementsDelivered.sum(), this.settlementsDropped.sum(),
				this.amountsMismatched.sum(), this.duplicateSettlementsSent.sum(), this.deliveryFailures.sum());
	}

	record Snapshot(long approved, long declined, long transientFailures, long duplicateChargeAttempts,
			long settlementsDelivered, long settlementsDropped, long amountsMismatched, long duplicateSettlementsSent,
			long deliveryFailures) {
	}

}
