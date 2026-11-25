package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.api.PaymentsApi;
import dev.tkachuk.gateway.api.model.CreatePaymentRequestDto;
import dev.tkachuk.gateway.api.model.PaymentResponseDto;
import dev.tkachuk.gateway.merchant.CurrentMerchant;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentsController implements PaymentsApi {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PaymentService payments;

    PaymentsController(PaymentService payments) {
        this.payments = payments;
    }

    @Override
    public ResponseEntity<PaymentResponseDto> createPayment(String idempotencyKey,
            CreatePaymentRequestDto createPaymentRequestDto) {
        PaymentResult result = payments.create(CurrentMerchant.get().id(), idempotencyKey,
                PaymentMapper.toNewPayment(createPaymentRequestDto));
        PaymentResponseDto body = PaymentMapper.toResponse(result.payment());
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/payments/" + body.getId())).body(body);
    }

    @Override
    public ResponseEntity<PaymentResponseDto> getPayment(UUID paymentId) {
        Payment payment = payments.get(CurrentMerchant.get().id(), paymentId);
        return ResponseEntity.ok(PaymentMapper.toResponse(payment));
    }
}
