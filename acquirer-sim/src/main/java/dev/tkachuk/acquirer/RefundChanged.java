package dev.tkachuk.acquirer;

/** A refund that was pending is finished: it succeeded or failed. */
record RefundChanged(Refund refund) {
}
