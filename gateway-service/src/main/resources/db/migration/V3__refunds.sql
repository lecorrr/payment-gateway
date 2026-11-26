-- Refunds of SUCCEEDED payments, each for all or part of the amount, sent to the provider that took the charge. A
-- refund is PROCESSING until the provider says how it ended; request_fingerprint tells a retry with the same
-- Idempotency-Key from a different request, as with payments.
CREATE TABLE refunds (
    id                  UUID         PRIMARY KEY,
    payment_id          UUID         NOT NULL REFERENCES payments (id),
    merchant_id         UUID         NOT NULL REFERENCES merchants (id),
    idempotency_key     VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64)     NOT NULL,
    amount              BIGINT       NOT NULL CHECK (amount > 0),
    currency            CHAR(3)      NOT NULL CHECK (currency IN ('UAH', 'USD', 'EUR')),
    status              VARCHAR(16)  NOT NULL CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    provider            VARCHAR(32)  NOT NULL,
    provider_reference  VARCHAR(100),
    -- why a FAILED refund did not go through: the provider refused it, or one of the gateway's own reasons
    failure_code        VARCHAR(64),
    next_check_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT refunds_one_per_idempotency_key UNIQUE (merchant_id, idempotency_key),
    -- a PROCESSING refund always has its next check, a finished one never
    CONSTRAINT refunds_next_check_while_processing CHECK ((status = 'PROCESSING') = (next_check_at IS NOT NULL)),
    CONSTRAINT refunds_failure_code_when_failed CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);

-- The checker's query, and a payment's refunds.
CREATE INDEX refunds_due_for_check ON refunds (next_check_at) WHERE next_check_at IS NOT NULL;
CREATE INDEX refunds_by_payment ON refunds (payment_id);

-- What has come back to the card so far: the sum of the payment's SUCCEEDED refunds, kept on the payment so reading
-- it needs no sum. It can never pass the amount, whatever a bug elsewhere does.
ALTER TABLE payments ADD COLUMN refunded_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payments ADD CONSTRAINT payments_refunded_within_amount CHECK (refunded_amount BETWEEN 0 AND amount);

-- A refund's webhook events belong to its payment, as the payment's own do.
COMMENT ON COLUMN webhook_events.payment_id
    IS 'The payment the event is about, or the payment of the refund it is about';
