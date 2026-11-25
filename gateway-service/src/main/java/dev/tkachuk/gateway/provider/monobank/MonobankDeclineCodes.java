package dev.tkachuk.gateway.provider.monobank;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * monobank's payment error codes (the errCode of an invoice, from monobank's list of payment errors) as the
 * gateway's decline codes, so a merchant sees the same codes whichever provider charged the card. A code not listed
 * here is the card's bank refusing for a reason of its own, and becomes do_not_honor.
 */
final class MonobankDeclineCodes {

    static final String DO_NOT_HONOR = "do_not_honor";

    private static final Map<String, String> BY_ERR_CODE = byErrCode();

    private MonobankDeclineCodes() {
    }

    static String of(@Nullable String errCode) {
        if (errCode == null) {
            return DO_NOT_HONOR;
        }
        return BY_ERR_CODE.getOrDefault(errCode.strip(), DO_NOT_HONOR);
    }

    private static Map<String, String> byErrCode() {
        Map<String, String> codes = new HashMap<>();
        add(codes, "insufficient_funds", "59");
        add(codes, "incorrect_cvc", "80", "81", "1200");
        add(codes, "expired_card", "51");
        // 3-D Secure not passed, broken off on the way, or cancelled by the customer.
        add(codes, "authentication_failed",
                "84", "1034", "1035", "1045", "1054", "1421", "1422", "1425", "1429", "1458", "8002", "8004");
        // The payment link ran out before the customer finished.
        add(codes, "authentication_expired", "8001");
        // monobank's side rather than the card: technical faults, the merchant's settings and limits, routing, and
        // requests monobank could not take.
        add(codes, "processing_error",
                "67", "73", "74", "98", "1000", "1001", "1005", "1010", "1014", "1036", "1044", "1053", "1066",
                "1077", "1115", "1121", "1145", "1420", "8003", "8005", "8006");
        return Map.copyOf(codes);
    }

    private static void add(Map<String, String> codes, String declineCode, String... errCodes) {
        for (String errCode : errCodes) {
            if (codes.putIfAbsent(errCode, declineCode) != null) {
                throw new IllegalStateException("monobank's errCode " + errCode + " is listed twice");
            }
        }
    }
}
