package dev.tkachuk.acquirer;

import dev.tkachuk.acquirer.ChargeStore.StoredCharge;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The 3-D Secure challenge page, as the card's bank would show it: the cardholder passes or fails it and is sent on
 * to the charge's return_url. Browsers open it, so it needs no API key (BearerTokenFilter covers /v1 only), and it
 * works without JavaScript.
 */
@RestController
class ChallengeController {

    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
    // Charge ids are ch_ and 24 hex digits, so a valid one is safe to put into a page as it is.
    private static final Pattern CHARGE_ID = Pattern.compile("ch_[0-9a-f]{24}");

    private final ChargeService charges;

    ChallengeController(ChargeService charges) {
        this.charges = charges;
    }

    @GetMapping(ChargeService.CHALLENGE_PATH + "{chargeId}")
    ResponseEntity<String> show(@PathVariable String chargeId) {
        Optional<StoredCharge> stored = CHARGE_ID.matcher(chargeId).matches()
                ? charges.findById(chargeId)
                : Optional.empty();
        if (stored.isEmpty()) {
            return page(HttpStatus.NOT_FOUND, ChallengePage.notFound());
        }
        return page(HttpStatus.OK, ChallengePage.of(stored.get()));
    }

    @PostMapping(ChargeService.CHALLENGE_PATH + "{chargeId}")
    ResponseEntity<String> complete(@PathVariable String chargeId, @RequestParam String outcome) {
        if (!outcome.equals("pass") && !outcome.equals("fail")) {
            return page(HttpStatus.BAD_REQUEST, ChallengePage.badOutcome());
        }
        Optional<StoredCharge> stored = CHARGE_ID.matcher(chargeId).matches()
                ? charges.completeChallenge(chargeId, outcome.equals("pass"))
                : Optional.empty();
        if (stored.isEmpty()) {
            return page(HttpStatus.NOT_FOUND, ChallengePage.notFound());
        }
        String returnUrl = stored.get().returnUrl();
        if (returnUrl == null) {
            return page(HttpStatus.OK, ChallengePage.of(stored.get()));
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(returnUrl)).build();
    }

    private static ResponseEntity<String> page(HttpStatus status, String html) {
        return ResponseEntity.status(status).contentType(HTML).body(html);
    }
}
