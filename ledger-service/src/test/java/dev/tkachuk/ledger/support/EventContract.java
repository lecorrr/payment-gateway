package dev.tkachuk.ledger.support;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;

/**
 * The examples of docs/payment-events.yaml, the AsyncAPI contract of the payment-events topic: each message's
 * headers and data, as the gateway sends them. Read from the repository's docs folder; Maven runs the tests in the
 * module's folder.
 */
public final class EventContract {

    private static final Path CONTRACT = Path.of("..", "docs", "payment-events.yaml");

    private final Map<String, Object> messages;

    private EventContract(Map<String, Object> messages) {
        this.messages = messages;
    }

    public static EventContract read() {
        try (Reader reader = Files.newBufferedReader(CONTRACT, StandardCharsets.UTF_8)) {
            Map<String, Object> document = new Yaml().load(reader);
            return new EventContract(map(map(document.get("components")).get("messages")));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read " + CONTRACT.toAbsolutePath()
                    + ": run the tests from the ledger-service folder, as Maven does", e);
        }
    }

    /** The examples of the message, such as paymentEvent, in the order the contract lists them. */
    public List<Example> examples(String message) {
        Object examples = map(messages.get(message)).get("examples");
        if (!(examples instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("Message " + message + " has no examples in " + CONTRACT);
        }
        return list.stream()
                .map(EventContract::map)
                .map(example -> new Example(String.valueOf(example.get("name")), strings(map(example.get("headers"))),
                        map(example.get("payload"))))
                .toList();
    }

    /** An example: its name, its headers and its data. */
    public record Example(String name, Map<String, String> headers, Map<String, Object> payload) {
    }

    // The headers as the strings they are on the record; YAML would read some, such as '1.0', as other types.
    private static Map<String, String> strings(Map<String, Object> headers) {
        return headers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> String.valueOf(entry.getValue())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Expected a mapping in " + CONTRACT + ", got " + value);
        }
        return (Map<String, Object>) value;
    }
}
