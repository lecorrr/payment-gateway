package dev.tkachuk.gateway.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.gateway.common.Currency;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class EventOutboxTest {

    private static final UUID EVENT = UUID.fromString("6f1c2b7e-3a5d-4b8e-9c0f-1a2b3c4d5e6f");
    private static final UUID PAYMENT = UUID.fromString("0d7f5e2a-8c41-4c1b-9a6e-5b3f2d1c0e9a");
    private static final UUID MERCHANT = UUID.fromString("3b9d6c4e-1f2a-4e5b-8c7d-9e0f1a2b3c4d");
    private static final Instant TIME = Instant.parse("2026-11-26T09:15:02.481233789Z");
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final EventOutboxRepository repository = mock(EventOutboxRepository.class);
    private final Tracer tracer = mock(Tracer.class);
    private final Propagator propagator = mock(Propagator.class);
    private final ObjectProvider<Tracer> tracers = provider();
    private final ObjectProvider<Propagator> propagators = provider();
    private final EventOutbox outbox = new EventOutbox(repository, JsonMapper.builder().build(), tracers, propagators);

    @BeforeEach
    void startTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(tracers.getIfAvailable()).thenReturn(tracer);
        when(propagators.getIfAvailable()).thenReturn(propagator);
    }

    @AfterEach
    void endTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void shouldStoreEventWithItsDataAsJsonAndTraceContextOfCurrentSpan() {
        currentSpanPropagatesAs(TRACEPARENT);

        outbox.add(EVENT, PAYMENT, "payment.succeeded", TIME, data());

        OutboxEvent stored = stored();
        assertThat(stored.id()).isEqualTo(EVENT);
        assertThat(stored.paymentId()).isEqualTo(PAYMENT);
        assertThat(stored.type()).isEqualTo("payment.succeeded");
        assertThat(JsonPath.<String>read(stored.payload(), "$.paymentId")).isEqualTo(PAYMENT.toString());
        assertThat(JsonPath.<String>read(stored.payload(), "$.merchantId")).isEqualTo(MERCHANT.toString());
        assertThat(JsonPath.<Integer>read(stored.payload(), "$.amount")).isEqualTo(15000);
        assertThat(stored.traceparent()).isEqualTo(TRACEPARENT);
        // PostgreSQL keeps microseconds.
        assertThat(stored.createdAt()).isEqualTo(Instant.parse("2026-11-26T09:15:02.481233Z"));
    }

    @Test
    void shouldStoreNoTraceContextWhenNothingIsTraced() {
        when(tracer.currentSpan()).thenReturn(null);

        outbox.add(EVENT, PAYMENT, "payment.succeeded", TIME, data());

        assertThat(stored().traceparent()).isNull();
    }

    @Test
    void shouldStoreNoTraceContextWithoutTracing() {
        when(tracers.getIfAvailable()).thenReturn(null);

        outbox.add(EVENT, PAYMENT, "payment.succeeded", TIME, data());

        assertThat(stored().traceparent()).isNull();
    }

    // traceparent of version 00 has 55 characters; anything longer would not fit the column.
    @Test
    void shouldStoreNoTraceContextLongerThanW3cVersion00() {
        currentSpanPropagatesAs(TRACEPARENT + "-extra");

        outbox.add(EVENT, PAYMENT, "payment.succeeded", TIME, data());

        assertThat(stored().traceparent()).isNull();
    }

    @Test
    void shouldRefuseEventOutsideTransactionOfItsChange() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatIllegalStateException()
                .isThrownBy(() -> outbox.add(EVENT, PAYMENT, "payment.succeeded", TIME, data()))
                .withMessageContaining("transaction");
        verifyNoInteractions(repository);
    }

    private void currentSpanPropagatesAs(String traceparent) {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        doAnswer(invocation -> {
            Propagator.Setter<Map<String, String>> setter = invocation.getArgument(2);
            setter.set(invocation.getArgument(1), "traceparent", traceparent);
            return null;
        }).when(propagator).inject(any(), any(), any());
    }

    private OutboxEvent stored() {
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).insert(event.capture());
        return event.getValue();
    }

    private static PaymentEventData data() {
        return new PaymentEventData(PAYMENT, MERCHANT, "ORDER-1001", "SUCCEEDED", 15000, Currency.UAH, "monobank",
                "inv_1", null, 0, TIME);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }
}
