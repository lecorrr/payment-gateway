package dev.tkachuk.acquirer;

import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/refunds")
class RefundController {

    private final RefundService refunds;

    RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @PostMapping
    ResponseEntity<?> create(@Valid @RequestBody RefundRequest request) {
        RefundService.Result result = refunds.refund(request, false);
        return switch (result.kind()) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).body(result.refund());
            case REPLAYED -> ResponseEntity.ok(result.refund());
            case CONFLICT -> error(HttpStatus.CONFLICT, "reference_reused",
                    "This reference belongs to a different refund");
            case NO_CHARGE -> error(HttpStatus.NOT_FOUND, "not_found", "No charge has this id");
            case NOT_REFUNDABLE -> error(HttpStatus.CONFLICT, "charge_not_refundable",
                    "Only an approved charge can be refunded");
            case TOO_LARGE -> error(HttpStatusCode.valueOf(422), "amount_too_large",
                    "The charge has less than this left to refund");
        };
    }

    @GetMapping
    ResponseEntity<?> find(@RequestParam String reference) {
        Optional<Refund> refund = refunds.find(reference);
        if (refund.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "not_found", "No refund has this reference");
        }
        return ResponseEntity.ok(refund.get());
    }

    private static ResponseEntity<SimError> error(HttpStatusCode status, String code, String message) {
        return ResponseEntity.status(status).body(new SimError(code, message));
    }
}
