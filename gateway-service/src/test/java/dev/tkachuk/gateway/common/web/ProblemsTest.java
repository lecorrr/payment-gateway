package dev.tkachuk.gateway.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemsTest {

    @Test
    void shouldNameTypeAndTitleAfterCode() {
        ProblemDetail problem = Problems.of(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "The API key is missing");

        assertThat(problem.getType()).hasToString("urn:payment-gateway:error:unauthorized");
        assertThat(problem.getTitle()).isEqualTo("Unauthorized");
        assertThat(problem.getProperties()).containsEntry("code", "UNAUTHORIZED");
    }

    @Test
    void shouldGiveFieldsOfProblemAsMap() {
        ProblemDetail problem = Problems.of(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Used for another request");

        assertThat(Problems.asMap(problem))
                .containsEntry("type", "urn:payment-gateway:error:idempotency-key-reused")
                .containsEntry("title", "Idempotency key reused")
                .containsEntry("status", 409)
                .containsEntry("detail", "Used for another request")
                .containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void shouldGiveProblemWithoutTypeAsAboutBlank() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);

        assertThat(Problems.asMap(problem))
                .containsEntry("type", "about:blank")
                .containsEntry("status", 401)
                .doesNotContainKey("code");
    }
}
