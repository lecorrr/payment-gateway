package dev.tkachuk.acquirer;

/** The body of every error answer: a short code and a sentence. */
record SimError(String error, String message) {
}
