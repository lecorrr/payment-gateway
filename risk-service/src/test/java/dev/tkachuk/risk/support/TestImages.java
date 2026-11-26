package dev.tkachuk.risk.support;

/** Image tags are set once in the parent pom.xml and passed to the tests as system properties. */
public final class TestImages {

    private TestImages() {
    }

    public static String fromSystemProperty(String property) {
        String image = System.getProperty(property);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("System property " + property + " is not set. Run the tests through Maven "
                    + "(./mvnw verify), or pass -D" + property + " with the tag from pom.xml.");
        }
        return image;
    }
}
