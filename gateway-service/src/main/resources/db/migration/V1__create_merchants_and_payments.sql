-- Merchants authenticate with an API key; only its SHA-256 (hex) is stored.
CREATE TABLE merchants (
    id           UUID PRIMARY KEY,
    code         VARCHAR(64)  NOT NULL UNIQUE,
    name         VARCHAR(200) NOT NULL,
    api_key_hash CHAR(64)     NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ  NOT NULL
);

-- One row per payment attempt. Of the card only the brand, the first six and the last four digits are kept;
-- request_fingerprint is an HMAC of the request, to tell a retry from a different request with the same key.
CREATE TABLE payments (
    id                  UUID PRIMARY KEY,
    merchant_id         UUID         NOT NULL REFERENCES merchants (id),
    idempotency_key     VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64)     NOT NULL,
    order_id            VARCHAR(64)  NOT NULL,
    amount              BIGINT       NOT NULL CHECK (amount > 0),
    currency            CHAR(3)      NOT NULL CHECK (currency IN ('UAH', 'USD', 'EUR')),
    description         VARCHAR(140),
    status              VARCHAR(16)  NOT NULL CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'DECLINED', 'FAILED')),
    card_brand          VARCHAR(16)  NOT NULL CHECK (card_brand IN ('VISA', 'MASTERCARD', 'OTHER')),
    card_bin            CHAR(6)      NOT NULL,
    card_last4          CHAR(4)      NOT NULL,
    provider            VARCHAR(32)  NOT NULL,
    provider_reference  VARCHAR(100),
    -- the provider's decline code, or the gateway's reason for a FAILED payment
    decline_code        VARCHAR(64),
    next_check_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT payments_one_per_idempotency_key UNIQUE (merchant_id, idempotency_key),
    -- a PROCESSING payment always has its next check, a finished one never
    CONSTRAINT payments_next_check_while_processing CHECK ((status = 'PROCESSING') = (next_check_at IS NOT NULL))
);

-- The checker's query: PROCESSING payments whose next check is due.
CREATE INDEX payments_due_for_check ON payments (next_check_at) WHERE status = 'PROCESSING';
