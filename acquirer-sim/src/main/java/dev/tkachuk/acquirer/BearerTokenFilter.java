package dev.tkachuk.acquirer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every /v1 request needs "Authorization: Bearer" with sim.api-key. */
@Component
class BearerTokenFilter extends OncePerRequestFilter {

    private static final String UNAUTHORIZED_BODY =
            "{\"error\": \"unauthorized\", \"message\": \"Send the API key as a Bearer token\"}";

    private final byte[] expectedHeader;

    BearerTokenFilter(SimProperties properties) {
        this.expectedHeader = ("Bearer " + properties.apiKey()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        // MessageDigest.isEqual takes as long for any header, so the key can't be guessed byte by byte from timing.
        if (header == null || !MessageDigest.isEqual(expectedHeader, header.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
