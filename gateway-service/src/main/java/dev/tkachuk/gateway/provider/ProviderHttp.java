package dev.tkachuk.gateway.provider;

import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** What every connector's HTTP calls share: the timeouts, and telling apart the ways a call can go wrong. */
public final class ProviderHttp {

    private ProviderHttp() {
    }

    /** The builder, set to give up connecting after connectTimeout and waiting for an answer after readTimeout. */
    public static RestClient.Builder withTimeouts(RestClient.Builder builder, Duration connectTimeout,
            Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.requestFactory(requestFactory);
    }

    /**
     * Connection refused, an unknown host or a connect timeout: the request never left this machine, so the
     * provider never got it. Anything later, such as a read timeout or a dropped connection, can happen after the
     * provider got the request.
     */
    public static boolean neverConnected(ResourceAccessException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * For a log line: an HTTP error by its status only, since its message quotes the response body; an I/O error by
     * its type, since some have no message at all (a refused connection).
     */
    public static String describe(RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        Throwable cause = e.getCause();
        if (e instanceof ResourceAccessException && cause != null) {
            String type = cause.getClass().getSimpleName();
            return cause.getMessage() == null ? type : type + ": " + cause.getMessage();
        }
        return e.getMessage();
    }

    /** Whether a URL from a provider is one a browser can be sent to: absolute http(s), with a host. */
    public static boolean isHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
