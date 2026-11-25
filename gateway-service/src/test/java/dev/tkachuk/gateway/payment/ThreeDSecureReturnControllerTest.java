package dev.tkachuk.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ThreeDSecureReturnControllerTest {

    private static final UUID PAYMENT_ID = UUID.fromString("7f1c2b3a-0000-4000-8000-000000000001");
    private static final String PARAMETER = "paymentId=" + PAYMENT_ID;

    static Stream<Arguments> returnUrls() {
        return Stream.of(
                Arguments.of("https://shop.example.com/done", "https://shop.example.com/done?" + PARAMETER),
                Arguments.of("https://shop.example.com", "https://shop.example.com?" + PARAMETER),
                Arguments.of("http://localhost:8092/return?order=1",
                        "http://localhost:8092/return?order=1&" + PARAMETER),
                Arguments.of("https://shop.example.com/done?q=a%20b#top",
                        "https://shop.example.com/done?q=a%20b&" + PARAMETER + "#top"));
    }

    @ParameterizedTest
    @MethodSource("returnUrls")
    void shouldAddPaymentIdAndKeepTheRestAsItCame(String returnUrl, String expected) {
        assertThat(ThreeDSecureReturnController.withPaymentId(returnUrl, PAYMENT_ID)).hasToString(expected);
    }
}
