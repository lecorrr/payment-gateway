-- The outbox of the payment-events topic on Kafka: every event a payment or refund reaches, written in the same
-- transaction as the change, whatever the merchant's webhooks. The publisher sends them in seq order and marks them
-- published; id is the event's CloudEvents id, the same as its webhook's webhook-id. payload is the event's data as
-- it goes onto the topic, so it is text, not jsonb. traceparent is the W3C trace context of the request that made
-- the change, so the consumers' work joins its trace.
CREATE TABLE event_outbox (
    seq          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id           UUID         NOT NULL UNIQUE,
    payment_id   UUID         NOT NULL REFERENCES payments (id),
    type         VARCHAR(64)  NOT NULL,
    payload      TEXT         NOT NULL,
    traceparent  VARCHAR(55),
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);

-- The publisher's query: the events not published yet, oldest first.
CREATE INDEX event_outbox_unpublished ON event_outbox (seq) WHERE published_at IS NULL;
-- Published events are deleted after a while (gateway.events.keep-published-for).
CREATE INDEX event_outbox_published ON event_outbox (published_at) WHERE published_at IS NOT NULL;
