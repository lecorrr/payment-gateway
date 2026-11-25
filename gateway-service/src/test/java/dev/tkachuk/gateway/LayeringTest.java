package dev.tkachuk.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The module rules of ARCHITECTURE.md, checked on the import lines of the main sources. */
class LayeringTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java", "dev", "tkachuk", "gateway");
    private static final String BASE_PACKAGE = "dev.tkachuk.gateway.";
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);

    // "api" is the code generated from the OpenAPI file.
    private static final Map<String, Set<String>> MAY_DEPEND_ON = Map.of(
            "common", Set.of(),
            "card", Set.of("common"),
            "webhook", Set.of("common"),
            "merchant", Set.of("common", "webhook"),
            "provider", Set.of("common", "card"),
            "payment", Set.of("common", "card", "merchant", "provider", "webhook", "api"));

    private static List<SourceFile> sources;

    @BeforeAll
    static void readSources() throws IOException {
        assertThat(MAIN_SOURCES).as("LayeringTest reads the sources: run it from the gateway-service folder")
                .isDirectory();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).map(SourceFile::read).toList();
        }
        assertThat(sources).as("Java sources under " + MAIN_SOURCES).isNotEmpty();
    }

    @Test
    void shouldHaveRuleForEveryModule() {
        List<String> withoutRule = sources.stream()
                .map(SourceFile::module)
                .filter(module -> !module.isEmpty() && !MAY_DEPEND_ON.containsKey(module))
                .distinct()
                .toList();

        assertThat(withoutRule).as("a new module needs a line in MAY_DEPEND_ON and in ARCHITECTURE.md").isEmpty();
    }

    @Test
    void shouldImportOnlyAllowedModules() {
        List<String> violations = sources.stream()
                .filter(source -> MAY_DEPEND_ON.containsKey(source.module()))
                .flatMap(source -> source.gatewayImports()
                        .filter(module -> !module.equals(source.module()))
                        .filter(module -> !MAY_DEPEND_ON.get(source.module()).contains(module))
                        .map(module -> source.path() + " imports " + module))
                .toList();

        assertThat(violations).isEmpty();
    }

    @Test
    void shouldKeepCardFreeOfFrameworks() {
        List<String> violations = sources.stream()
                .filter(source -> source.module().equals("card"))
                .flatMap(source -> source.imports().stream()
                        .filter(name -> name.startsWith("org.springframework.") || name.startsWith("jakarta."))
                        .map(name -> source.path() + " imports " + name))
                .toList();

        assertThat(violations).isEmpty();
    }

    // Spring wires a provider's adapter in; nothing else may import it, so adding a provider touches no other module.
    @Test
    void shouldImportProviderAdaptersFromNowhereElse() {
        Pattern adapterPackage = Pattern.compile("dev\\.tkachuk\\.gateway\\.provider\\.([a-z][a-z0-9]*)\\.");
        List<String> violations = sources.stream()
                .flatMap(source -> source.imports().stream()
                        .map(adapterPackage::matcher)
                        .filter(Matcher::lookingAt)
                        .filter(matcher -> !source.packageName().startsWith(
                                BASE_PACKAGE + "provider." + matcher.group(1)))
                        .map(matcher -> source.path() + " imports " + matcher.group()))
                .toList();

        assertThat(violations).isEmpty();
    }

    private record SourceFile(Path path, String packageName, String module, List<String> imports) {

        static SourceFile read(Path path) {
            try {
                String text = Files.readString(path);
                Path relative = MAIN_SOURCES.relativize(path.getParent());
                boolean inBasePackage = relative.toString().isEmpty();
                String module = inBasePackage ? "" : relative.getName(0).toString();
                String packageName = inBasePackage
                        ? BASE_PACKAGE.substring(0, BASE_PACKAGE.length() - 1)
                        : BASE_PACKAGE + relative.toString().replace(path.getFileSystem().getSeparator(), ".");
                List<String> imports = IMPORT.matcher(text).results().map(match -> match.group(1)).toList();
                return new SourceFile(path, packageName, module, imports);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Stream<String> gatewayImports() {
            return imports.stream()
                    .filter(name -> name.startsWith(BASE_PACKAGE))
                    .map(name -> name.substring(BASE_PACKAGE.length()).split("\\.")[0]);
        }
    }
}
