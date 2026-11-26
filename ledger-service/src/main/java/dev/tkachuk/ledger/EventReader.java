package dev.tkachuk.ledger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.tkachuk.ledger.LedgerEvent.NotBooked;
import dev.tkachuk.ledger.LedgerEvent.PaymentSucceeded;
import dev.tkachuk.ledger.LedgerEvent.RefundSucceeded;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a record of the payment-events topic as docs/payment-events.yaml describes it: a CloudEvent 1.0 in binary
 * mode, keyed by the payment's id. A succeeded payment or refund must carry every value its entry needs, and the
 * record's key must be its payment; any other type is taken without its data, which may change. What doesn't fit
 * is a MalformedEventException.
 */
@Component
class EventReader {

    static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    static final String REFUND_SUCCEEDED = "refund.succeeded";

    private static final String SPEC_VERSION = "1.0";
    private static final String SUCCEEDED = "SUCCEEDED";
    // What the tables hold: a type of up to 64 characters, a provider of up to 32, a three-letter currency. The
    // contract lists the providers and currencies of today; others to come are booked as they are.
    private static final int MAX_TYPE_LENGTH = 64;
    private static final Pattern PROVIDER = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final ObjectReader payments;
    private final ObjectReader refunds;

    // An amount with a fraction is refused, rather than cut to a whole number as the mapper would by default.
    EventReader(JsonMapper jsonMapper) {
        this.payments = jsonMapper.readerFor(PaymentData.class).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.refunds = jsonMapper.readerFor(RefundData.class).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    LedgerEvent read(ConsumerRecord<String, String> record) {
        String specVersion = header(record, "ce_specversion");
        if (!SPEC_VERSION.equals(specVersion)) {
            throw new MalformedEventException("ce_specversion is " + specVersion + ", not " + SPEC_VERSION);
        }
        UUID eventId = uuid(header(record, "ce_id"), "ce_id");
        String type = header(record, "ce_type");
        if (type.isEmpty() || type.length() > MAX_TYPE_LENGTH) {
            throw new MalformedEventException("Event " + eventId + " has a ce_type of " + type.length()
                    + " characters, not 1 to " + MAX_TYPE_LENGTH);
        }
        UUID key = uuid(record.key(), "the record's key");
        String value = record.value();
        if (value == null) {
            throw new MalformedEventException("Event " + eventId + " has no data");
        }
        return switch (type) {
            case PAYMENT_SUCCEEDED ->
                    paymentSucceeded(eventId, key, parse(payments, value, PaymentData.class, eventId));
            case REFUND_SUCCEEDED ->
                    refundSucceeded(eventId, key, parse(refunds, value, RefundData.class, eventId));
            default -> new NotBooked(eventId, type, key);
        };
    }

    private static PaymentSucceeded paymentSucceeded(UUID eventId, UUID key, PaymentData data) {
        UUID paymentId = required(data.paymentId(), "paymentId", eventId);
        requireKeyIsPayment(key, paymentId, eventId);
        requireSucceeded(data.status(), eventId);
        return new PaymentSucceeded(eventId, paymentId, required(data.merchantId(), "merchantId", eventId),
                provider(data.provider(), eventId), currency(data.currency(), eventId), amount(data.amount(), eventId),
                required(data.occurredAt(), "occurredAt", eventId));
    }

    private static RefundSucceeded refundSucceeded(UUID eventId, UUID key, RefundData data) {
        UUID paymentId = required(data.paymentId(), "paymentId", eventId);
        requireKeyIsPayment(key, paymentId, eventId);
        requireSucceeded(data.status(), eventId);
        return new RefundSucceeded(eventId, required(data.refundId(), "refundId", eventId), paymentId,
                required(data.merchantId(), "merchantId", eventId), provider(data.provider(), eventId),
                currency(data.currency(), eventId), amount(data.amount(), eventId),
                required(data.occurredAt(), "occurredAt", eventId));
    }

    private static <T> T parse(ObjectReader reader, String value, Class<T> type, UUID eventId) {
        try {
            return type.cast(reader.readValue(value));
        } catch (JacksonException e) {
            throw new MalformedEventException("Event " + eventId + " has data that is no " + type.getSimpleName()
                    + ": " + e.getOriginalMessage(), e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            throw new MalformedEventException("The record at offset " + record.offset() + " of partition "
                    + record.partition() + " has no " + name + " header");
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static UUID uuid(@Nullable String value, String what) {
        if (value == null) {
            throw new MalformedEventException(what + " is missing");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException(what + " is no UUID: " + value, e);
        }
    }

    private static <T> T required(@Nullable T value, String field, UUID eventId) {
        if (value == null) {
            throw new MalformedEventException("Event " + eventId + " has no " + field);
        }
        return value;
    }

    // The key decides the partition, and so the order the payment's events are read in.
    private static void requireKeyIsPayment(UUID key, UUID paymentId, UUID eventId) {
        if (!key.equals(paymentId)) {
            throw new MalformedEventException("Event " + eventId + " is keyed " + key + ", not by its payment "
                    + paymentId);
        }
    }

    private static void requireSucceeded(@Nullable String status, UUID eventId) {
        if (!SUCCEEDED.equals(status)) {
            throw new MalformedEventException("Event " + eventId + " says succeeded, but its status is " + status);
        }
    }

    private static String provider(@Nullable String provider, UUID eventId) {
        if (provider == null || !PROVIDER.matcher(provider).matches()) {
            throw new MalformedEventException("Event " + eventId + " has no provider name of up to 32 lowercase "
                    + "letters, digits and '-': " + provider);
        }
        return provider;
    }

    private static String currency(@Nullable String currency, UUID eventId) {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new MalformedEventException("Event " + eventId + " has no ISO 4217 currency: " + currency);
        }
        return currency;
    }

    private static long amount(long amount, UUID eventId) {
        if (amount <= 0) {
            throw new MalformedEventException("Event " + eventId + " has an amount that isn't positive: " + amount);
        }
        return amount;
    }

    /** The data of a payment event, the fields the ledger uses; the contract's others are let through. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentData(@Nullable UUID paymentId, @Nullable UUID merchantId, @Nullable String status, long amount,
            @Nullable String currency, @Nullable String provider, @Nullable Instant occurredAt) {
    }

    /** The data of a refund event, the fields the ledger uses. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RefundData(@Nullable UUID refundId, @Nullable UUID paymentId, @Nullable UUID merchantId,
            @Nullable String status, long amount, @Nullable String currency, @Nullable String provider,
            @Nullable Instant occurredAt) {
    }
}
