-- Every event the ledger has taken from the payment-events topic, by its CloudEvents id: Kafka delivers at least
-- once, and an event that comes again finds its id here and is skipped. Where it was read from is kept for tracing
-- an entry back to its record.
CREATE TABLE processed_events (
    event_id      UUID         PRIMARY KEY,
    type          VARCHAR(64)  NOT NULL,
    payment_id    UUID         NOT NULL,
    topic         VARCHAR(249) NOT NULL,
    partition     INT          NOT NULL,
    record_offset BIGINT       NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL
);

-- A journal entry for each payment that succeeded and each refund that succeeded, made from its event. Its id is
-- the event's: one event, at most one entry.
CREATE TABLE journal_entries (
    id          UUID         PRIMARY KEY REFERENCES processed_events (event_id),
    kind        VARCHAR(16)  NOT NULL CHECK (kind IN ('PAYMENT', 'REFUND')),
    merchant_id UUID         NOT NULL,
    payment_id  UUID         NOT NULL,
    refund_id   UUID,
    provider    VARCHAR(32)  NOT NULL,
    currency    CHAR(3)      NOT NULL,
    amount      BIGINT       NOT NULL CHECK (amount > 0),
    occurred_at TIMESTAMPTZ  NOT NULL,
    booked_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT journal_entries_refund_id_for_refunds CHECK ((kind = 'REFUND') = (refund_id IS NOT NULL))
);

CREATE INDEX journal_entries_by_payment ON journal_entries (payment_id);
CREATE INDEX journal_entries_by_merchant ON journal_entries (merchant_id, currency);

-- An entry's postings, in minor units of its currency: a debit is positive, a credit negative, and an entry's
-- postings add up to zero. merchant:<id> is what is owed to the merchant, provider:<name> what the provider owes.
CREATE TABLE postings (
    entry_id UUID        NOT NULL REFERENCES journal_entries (id),
    account  VARCHAR(80) NOT NULL,
    amount   BIGINT      NOT NULL CHECK (amount <> 0),
    PRIMARY KEY (entry_id, account)
);

CREATE INDEX postings_by_account ON postings (account);
