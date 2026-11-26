package dev.tkachuk.gateway.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EventsPropertiesTest {

    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final Duration WEEK = Duration.ofDays(7);

    @Test
    void shouldTakeSettingsOfApplicationYml() {
        EventsProperties properties = new EventsProperties(true, "payment-events", 3, (short) 1,
                Duration.ofMillis(200), 100, Duration.ofSeconds(15), WEEK);

        assertThat(properties.topic()).isEqualTo("payment-events");
        assertThat(properties.partitions()).isEqualTo(3);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"payment events", "payment/events", "événements"})
    void shouldRejectTopicKafkaWouldRefuse(String topic) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventsProperties(true, topic, 3, (short) 1, SECOND, 100, SECOND, WEEK))
                .withMessageContaining("gateway.events.topic");
    }

    @Test
    void shouldRejectCountsBelowOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventsProperties(true, "t", 0, (short) 1, SECOND, 100, SECOND, WEEK))
                .withMessageContaining("partitions");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventsProperties(true, "t", 3, (short) 0, SECOND, 100, SECOND, WEEK))
                .withMessageContaining("replicas");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventsProperties(true, "t", 3, (short) 1, SECOND, 0, SECOND, WEEK))
                .withMessageContaining("batch-size");
    }

    @Test
    void shouldRejectDurationsThatAreNotPositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventsProperties(true, "t", 3, (short) 1, Duration.ZERO, 100, SECOND, WEEK))
                .withMessageContaining("publish-interval");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventsProperties(true, "t", 3, (short) 1, SECOND, 100, null, WEEK))
                .withMessageContaining("send-timeout");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventsProperties(true, "t", 3, (short) 1, SECOND, 100, SECOND,
                        Duration.ofDays(-1)))
                .withMessageContaining("keep-published-for");
    }
}
