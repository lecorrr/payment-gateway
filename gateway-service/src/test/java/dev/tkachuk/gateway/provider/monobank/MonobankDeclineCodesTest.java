package dev.tkachuk.gateway.provider.monobank;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MonobankDeclineCodesTest {

    @ParameterizedTest
    @CsvSource({
            "59, insufficient_funds",
            "80, incorrect_cvc",
            "81, incorrect_cvc",
            "1200, incorrect_cvc",
            "51, expired_card",
            "1054, authentication_failed",
            "8002, authentication_failed",
            "8001, authentication_expired",
            "1000, processing_error",
            "8003, processing_error",
            "55, do_not_honor",
            "1407, do_not_honor",
            "9999, do_not_honor"})
    void shouldTurnErrCodeIntoGatewaysDeclineCode(String errCode, String declineCode) {
        assertThat(MonobankDeclineCodes.of(errCode)).isEqualTo(declineCode);
    }

    @Test
    void shouldDeclineAsDoNotHonorWithoutErrCode() {
        assertThat(MonobankDeclineCodes.of(null)).isEqualTo("do_not_honor");
        assertThat(MonobankDeclineCodes.of(" 59 ")).isEqualTo("insufficient_funds");
    }
}
