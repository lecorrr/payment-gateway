package dev.tkachuk.merchant;

/** A webhook that must not be trusted; the message says why, without any secret. */
class InvalidWebhookException extends RuntimeException {

    InvalidWebhookException(String message) {
        super(message);
    }
}
