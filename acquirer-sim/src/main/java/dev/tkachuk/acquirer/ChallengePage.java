package dev.tkachuk.acquirer;

import dev.tkachuk.acquirer.ChargeStore.StoredCharge;
import java.math.BigDecimal;
import org.springframework.web.util.HtmlUtils;

/**
 * The HTML of the challenge pages: plain forms that post the outcome, no JavaScript. Everything that comes from a
 * request is escaped; ids and amounts have fixed formats already.
 */
final class ChallengePage {

    private ChallengePage() {
    }

    /** The challenge with its two buttons while the charge waits for it, otherwise how it ended. */
    static String of(StoredCharge stored) {
        Charge charge = stored.charge();
        if (!charge.waitsForChallenge()) {
            String back = stored.returnUrl() == null
                    ? ""
                    : "<p><a href=\"" + HtmlUtils.htmlEscape(stored.returnUrl()) + "\">Back to the shop</a></p>";
            return document("Payment " + charge.status(),
                    "<p>This challenge is over: the payment was " + charge.status() + ".</p>" + back);
        }
        String action = ChargeService.CHALLENGE_PATH + charge.id();
        return document("Confirm your payment", """
                <p>acquirer-sim asks you, as the card's bank would, to confirm a payment of <b>%s %s</b>.</p>
                <form method="post" action="%s"><input type="hidden" name="outcome" value="pass">
                <button type="submit">Confirm</button></form>
                <form method="post" action="%s"><input type="hidden" name="outcome" value="fail">
                <button type="submit">Fail the challenge</button></form>
                """.formatted(BigDecimal.valueOf(charge.amount(), 2).toPlainString(), charge.currency(), action,
                action));
    }

    static String notFound() {
        return document("No such challenge", "<p>No charge has this challenge.</p>");
    }

    static String badOutcome() {
        return document("Bad request", "<p>The outcome must be pass or fail.</p>");
    }

    private static String document(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="utf-8"><title>%s</title></head>
                <body><h1>%s</h1>
                %s</body>
                </html>
                """.formatted(title, title, body);
    }
}
