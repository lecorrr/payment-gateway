package dev.tkachuk.gateway.common.error;

import java.util.Objects;

/** A business rule refused the request. The code is for programs, the message for the merchant's developer. */
public class GatewayException extends RuntimeException {

    private final ErrorCode code;

    public GatewayException(ErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }
}
