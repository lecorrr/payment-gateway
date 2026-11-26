package dev.tkachuk.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** A setting the ledger can't work with stops it at startup, naming the setting. */
class LedgerPropertiesTest {

    @Test
    void shouldTakeSettingsOfApplicationYml() {
        LedgerProperties properties = new LedgerProperties("payment-events", "payment-events-ledger-dlt", (short) 1);

        assertThat(properties.topic()).isEqualTo("payment-events");
        assertThat(properties.deadLetterTopic()).isEqualTo("payment-events-ledger-dlt");
        assertThat(properties.replicas()).isEqualTo((short) 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void shouldRefuseSettingsItCannotWorkWith(String what, String topic, String deadLetterTopic, short replicas,
            String why) {
        assertThatThrownBy(() -> new LedgerProperties(topic, deadLetterTopic, replicas))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(why);
    }

    static Stream<Arguments> shouldRefuseSettingsItCannotWorkWith() {
        return Stream.of(
                Arguments.of("no topic", null, "dlt", (short) 1, "ledger.topic"),
                Arguments.of("empty topic", "", "dlt", (short) 1, "ledger.topic"),
                Arguments.of("topic with a space", "payment events", "dlt", (short) 1, "ledger.topic"),
                Arguments.of("topic too long", "t".repeat(250), "dlt", (short) 1, "ledger.topic"),
                Arguments.of("no dead-letter topic", "payment-events", null, (short) 1, "ledger.dead-letter-topic"),
                Arguments.of("dead letters to the topic itself", "payment-events", "payment-events", (short) 1,
                        "must not be the topic it reads"),
                Arguments.of("no replicas", "payment-events", "dlt", (short) 0, "ledger.replicas"),
                Arguments.of("negative replicas", "payment-events", "dlt", (short) -1, "ledger.replicas"));
    }
}
