-- 3-D Secure: a payment can wait in REQUIRES_ACTION for the customer's challenge at action_url, and the customer's
-- browser goes on to the merchant's return_url afterwards.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('PROCESSING', 'REQUIRES_ACTION', 'SUCCEEDED', 'DECLINED', 'FAILED'));
ALTER TABLE payments ADD COLUMN return_url VARCHAR(2048);
ALTER TABLE payments ADD COLUMN action_url VARCHAR(2048);

-- Both waiting states are looked up by the checker, so both have their next check; a finished payment has none.
ALTER TABLE payments DROP CONSTRAINT payments_next_check_while_processing;
ALTER TABLE payments ADD CONSTRAINT payments_next_check_while_pending
    CHECK ((status IN ('PROCESSING', 'REQUIRES_ACTION')) = (next_check_at IS NOT NULL));
ALTER TABLE payments ADD CONSTRAINT payments_action_url_while_requires_action
    CHECK ((status = 'REQUIRES_ACTION') = (action_url IS NOT NULL));

-- The checker's query: payments whose next check is due, whichever of the two waiting states they are in.
DROP INDEX payments_due_for_check;
CREATE INDEX payments_due_for_check ON payments (next_check_at) WHERE next_check_at IS NOT NULL;

-- Where a merchant's webhooks go, and the whsec_ secret they are signed with. The gateway needs the secret itself
-- to sign, so it can't be stored as a hash; in production it would be encrypted at rest.
CREATE TABLE webhook_endpoints (
    merchant_id UUID          PRIMARY KEY REFERENCES merchants (id),
    url         VARCHAR(2048) NOT NULL,
    secret      VARCHAR(200)  NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL
);

-- The outbox: an event is written in the same transaction as the payment change it reports, and sent from here
-- until delivered or given up. payload is the exact body that is signed, so it is text, not jsonb, which would
-- reorder and respace it.
CREATE TABLE webhook_events (
    id              UUID         PRIMARY KEY,
    merchant_id     UUID         NOT NULL REFERENCES merchants (id),
    payment_id      UUID         NOT NULL REFERENCES payments (id),
    type            VARCHAR(64)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    attempts        INT          NOT NULL CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ,
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    delivered_at    TIMESTAMPTZ,
    -- a PENDING event always has its next attempt, a delivered or given-up one never
    CONSTRAINT webhook_events_next_attempt_while_pending CHECK ((status = 'PENDING') = (next_attempt_at IS NOT NULL))
);

-- The dispatcher's query: events whose next attempt is due.
CREATE INDEX webhook_events_due ON webhook_events (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE INDEX webhook_events_by_payment ON webhook_events (payment_id);
