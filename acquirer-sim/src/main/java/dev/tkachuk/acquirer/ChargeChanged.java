package dev.tkachuk.acquirer;

/** A charge's status changed after its first answer: its 3-D Secure challenge ended, or ran out. */
record ChargeChanged(Charge charge) {
}
