package dev.tkachuk.acquirer;

import java.net.URI;
import java.net.URISyntaxException;

final class Urls {

    private Urls() {
    }

    /** Bean validation's patterns only check a URL's scheme and that it has no spaces; this checks it has a host. */
    static boolean hasHost(String url) {
        try {
            return new URI(url).getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
