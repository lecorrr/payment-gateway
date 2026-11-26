package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.api.RefundsApi;
import dev.tkachuk.gateway.api.model.CreateRefundRequestDto;
import dev.tkachuk.gateway.api.model.RefundResponseDto;
import dev.tkachuk.gateway.merchant.CurrentMerchant;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefundsController implements RefundsApi {

    private final RefundService refunds;

    RefundsController(RefundService refunds) {
        this.refunds = refunds;
    }

    @Override
    public ResponseEntity<RefundResponseDto> createRefund(UUID paymentId, String idempotencyKey,
            CreateRefundRequestDto createRefundRequestDto) {
        RefundResult result = refunds.create(CurrentMerchant.get().id(), paymentId, idempotencyKey,
                createRefundRequestDto.getAmount());
        RefundResponseDto body = RefundMapper.toResponse(result.refund());
        if (result.replayed()) {
            return ResponseEntity.ok().header(PaymentsController.REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/payments/" + paymentId + "/refunds/" + body.getId()))
                .body(body);
    }

    @Override
    public ResponseEntity<List<RefundResponseDto>> listRefunds(UUID paymentId) {
        List<RefundResponseDto> body = refunds.list(CurrentMerchant.get().id(), paymentId).stream()
                .map(RefundMapper::toResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<RefundResponseDto> getRefund(UUID paymentId, UUID refundId) {
        return ResponseEntity.ok(RefundMapper.toResponse(refunds.get(CurrentMerchant.get().id(), paymentId,
                refundId)));
    }
}
