package dev.tkachuk.gateway.merchant;

import dev.tkachuk.gateway.common.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every request outside the public paths needs a merchant's API key in X-Api-Key. Paths are public by an allow
 * list, so a new endpoint is protected unless someone decides otherwise.
 */
@Component
class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Api-Key";

    // /3ds/ is where customers' browsers come back from 3-D Secure challenges, and /providers/ where providers send
    // their webhooks: neither has a merchant's key.
    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "/actuator/", "/swagger-ui", "/v3/api-docs", "/openapi/", "/3ds/", "/providers/");

    private final MerchantRepository merchants;
    private final JsonMapper jsonMapper;

    ApiKeyFilter(MerchantRepository merchants, JsonMapper jsonMapper) {
        this.merchants = merchants;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        Optional<Merchant> merchant = apiKey == null || apiKey.isBlank()
                ? Optional.empty()
                : merchants.findByApiKeyHash(ApiKeys.hash(apiKey));
        if (merchant.isEmpty()) {
            writeUnauthorized(response);
            return;
        }
        CurrentMerchant.set(request, merchant.get());
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        HttpStatusCode status = HttpStatusCode.valueOf(401);
        ProblemDetail problem = Problems.of(status, "UNAUTHORIZED",
                "Send a valid API key in the " + HEADER + " header");
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), Problems.asMap(problem));
    }
}
