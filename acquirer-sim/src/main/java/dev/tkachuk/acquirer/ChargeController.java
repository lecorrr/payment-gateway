package dev.tkachuk.acquirer;

import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/charges")
class ChargeController {

    private final ChargeService charges;

    ChargeController(ChargeService charges) {
        this.charges = charges;
    }

    @PostMapping
    ResponseEntity<?> create(@Valid @RequestBody ChargeRequest request) {
        if (request.returnUrl() != null && !Urls.hasHost(request.returnUrl())) {
            return ResponseEntity.badRequest()
                    .body(new SimError("invalid_request", "return_url must be an http(s) URL with a host"));
        }
        ChargeService.Result result = charges.charge(request);
        return switch (result.kind()) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).body(result.charge());
            case REPLAYED -> ResponseEntity.ok(result.charge());
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new SimError("reference_reused", "This reference belongs to a different charge"));
        };
    }

    @GetMapping
    ResponseEntity<?> find(@RequestParam String reference) {
        Optional<Charge> charge = charges.find(reference);
        if (charge.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new SimError("not_found", "No charge has this reference"));
        }
        return ResponseEntity.ok(charge.get());
    }
}
