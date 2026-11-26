package dev.tkachuk.gateway.common.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** The RFC 9457 problem details every error of the API is described with, plus a "code" for programs. */
public final class Problems {

    private Problems() {
    }

    /** VALIDATION_FAILED becomes type urn:payment-gateway:error:validation-failed, title "Validation failed". */
    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        String slug = code.toLowerCase(Locale.ROOT).replace('_', '-');
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:payment-gateway:error:" + slug));
        problem.setTitle(Character.toUpperCase(slug.charAt(0)) + slug.substring(1).replace('-', ' '));
        problem.setProperty("code", code);
        return problem;
    }

    /** The same fields as a map, for a filter that writes the response itself, before Spring MVC. */
    public static Map<String, Object> asMap(ProblemDetail problem) {
        Map<String, Object> fields = new LinkedHashMap<>();
        // RFC 9457: a problem with no type is of type about:blank. The ones of(...) makes always have their own.
        URI type = problem.getType();
        fields.put("type", type == null ? "about:blank" : type.toString());
        fields.put("title", problem.getTitle());
        fields.put("status", problem.getStatus());
        fields.put("detail", problem.getDetail());
        Map<String, Object> properties = problem.getProperties();
        if (properties != null) {
            fields.putAll(properties);
        }
        return fields;
    }
}
