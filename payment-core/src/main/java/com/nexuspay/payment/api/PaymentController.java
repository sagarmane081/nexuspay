package com.nexuspay.payment.api;

import com.nexuspay.common.idempotency.IdempotencyService;
import com.nexuspay.common.money.Currencies;
import com.nexuspay.common.money.Money;
import com.nexuspay.payment.api.PaymentDtos.CaptureRequest;
import com.nexuspay.payment.api.PaymentDtos.CreatePaymentRequest;
import com.nexuspay.payment.api.PaymentDtos.PaymentResponse;
import com.nexuspay.payment.api.PaymentDtos.RefundRequest;
import com.nexuspay.payment.api.PaymentDtos.RefundResponse;
import com.nexuspay.payment.api.PaymentDtos.ReverseRequest;
import com.nexuspay.payment.application.PaymentService;
import com.nexuspay.payment.domain.Payment;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

/**
 * Every endpoint here moves money, so every one of them requires an
 * {@code Idempotency-Key}. Making the header mandatory rather than optional is
 * deliberate: an optional safety mechanism is one that gets omitted by the
 * client least able to handle a duplicate charge.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService payments, IdempotencyService idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request,
            UriComponentsBuilder uri) {

        PaymentResponse body = idempotency.execute(
                idempotencyKey, "POST /payments", request, PaymentResponse.class,
                () -> {
                    Money amount = Money.of(request.amount(), Currencies.parse(request.currency()));
                    return PaymentResponse.from(payments.authorize(
                            request.cardId(), request.merchantId(), request.terminalId(), amount));
                });

        // A declined payment is a successful request with a negative outcome,
        // not a failed request. The caller gets 201 and reads the status.
        return ResponseEntity
                .created(uri.path("/api/v1/payments/{id}").build(body.paymentId()))
                .body(body);
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable UUID paymentId) {
        return PaymentResponse.from(payments.require(paymentId));
    }

    @PostMapping("/{paymentId}/capture")
    public PaymentResponse capture(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID paymentId,
            @Valid @RequestBody(required = false) CaptureRequest request) {

        // Scoped to this payment: the same key against a different payment is a
        // different operation and must not replay this one's response.
        return idempotency.execute(
                idempotencyKey, "POST /payments/" + paymentId + "/capture",
                request == null ? new CaptureRequest(null) : request,
                PaymentResponse.class,
                () -> {
                    Payment payment = payments.require(paymentId);
                    Money captureAmount = (request == null || request.amount() == null)
                            ? payment.authorizedAmount()
                            : Money.of(request.amount(), payment.amount().currency());
                    return PaymentResponse.from(payments.capture(paymentId, captureAmount));
                });
    }

    @PostMapping("/{paymentId}/refund")
    public RefundResponse refund(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID paymentId,
            @Valid @RequestBody RefundRequest request) {

        return idempotency.execute(
                idempotencyKey, "POST /payments/" + paymentId + "/refund", request,
                RefundResponse.class,
                () -> {
                    Money amount = Money.of(request.amount(), payments.require(paymentId).amount().currency());
                    return RefundResponse.from(payments.refund(paymentId, amount, request.reason()));
                });
    }

    @PostMapping("/{paymentId}/reverse")
    public PaymentResponse reverse(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID paymentId,
            @Valid @RequestBody ReverseRequest request) {

        return idempotency.execute(
                idempotencyKey, "POST /payments/" + paymentId + "/reverse", request,
                PaymentResponse.class,
                () -> PaymentResponse.from(payments.reverse(paymentId, request.reason())));
    }
}
