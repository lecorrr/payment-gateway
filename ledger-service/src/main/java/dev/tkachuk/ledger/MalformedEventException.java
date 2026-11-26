package dev.tkachuk.ledger;

/**
 * An event the ledger can't read: not a CloudEvent of version 1.0, data that isn't the contract's, or values that
 * can't be. Trying again won't change it, so it goes to the dead-letter topic at once, with this message.
 */
class MalformedEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    MalformedEventException(String message) {
        super(message);
    }

    MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
