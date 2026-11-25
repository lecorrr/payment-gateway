package dev.tkachuk.acquirer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every request to the monobank-style API needs X-Token with sim.api-key, as monobank's needs the merchant's token. */
@Component
class MonobankTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Token";

    private static final String FORBIDDEN_BODY =
            "{\"error\": \"forbidden\", \"message\": \"Send the API key in X-Token\"}";

    private final byte[] expectedToken;

    MonobankTokenFilter(SimProperties properties) {
        this.expectedToken = properties.apiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(MonobankApiController.PATH + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        // MessageDigest.isEqual takes as long for any token, so the key can't be guessed byte by byte from timing.
        if (token == null || !MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(FORBIDDEN_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
