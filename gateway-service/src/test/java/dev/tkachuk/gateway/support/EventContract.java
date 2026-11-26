package dev.tkachuk.gateway.support;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * docs/payment-events.yaml, the AsyncAPI contract of the payment-events topic, as the tests check against it: the
 * properties and required properties of its schemas. Read from the repository's docs folder; Maven runs the tests in
 * the module's folder.
 */
public final class EventContract {

    private static final Path CONTRACT = Path.of("..", "docs", "payment-events.yaml");

    private final Map<String, Object> schemas;

    private EventContract(Map<String, Object> schemas) {
        this.schemas = schemas;
    }

    public static EventContract read() {
        try (Reader reader = Files.newBufferedReader(CONTRACT, StandardCharsets.UTF_8)) {
            Map<String, Object> document = new Yaml().load(reader);
            return new EventContract(map(map(document.get("components")).get("schemas")));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read " + CONTRACT.toAbsolutePath()
                    + ": run the tests from the gateway-service folder, as Maven does", e);
        }
    }

    /** The names of the properties the schema lists. */
    public Set<String> properties(String schema) {
        return map(schema(schema).get("properties")).keySet();
    }

    /** The properties the schema requires. */
    public List<String> required(String schema) {
        Object required = schema(schema).get("required");
        if (!(required instanceof List<?> names)) {
            throw new IllegalStateException("Schema " + schema + " has no list of required properties");
        }
        return names.stream().map(String::valueOf).toList();
    }

    private Map<String, Object> schema(String name) {
        Object schema = schemas.get(name);
        if (schema == null) {
            throw new IllegalArgumentException("The contract has no schema " + name + "; it has " + schemas.keySet());
        }
        return map(schema);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Expected a mapping in " + CONTRACT + ", got " + value);
        }
        return (Map<String, Object>) value;
    }
}
