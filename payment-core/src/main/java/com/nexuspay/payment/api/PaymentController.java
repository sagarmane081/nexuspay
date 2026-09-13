package com.nexuspay.payment.api;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request,
                                                  UriComponentsBuilder uri) {
        Money amount = Money.of(request.amount(), Currencies.parse(request.currency()));

        Payment payment = payments.authorize(
                request.cardId(), request.merchantId(), request.terminalId(), amount);

        // A declined payment is a successful request with a negative outcome,
        // not a failed request. The caller gets 201 and reads the status.
        return ResponseEntity
                .created(uri.path("/api/v1/payments/{id}").build(payment.paymentId()))
                .body(PaymentResponse.from(payment));
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable UUID paymentId) {
        return PaymentResponse.from(payments.require(paymentId));
    }

    @PostMapping("/{paymentId}/capture")
    public PaymentResponse capture(@PathVariable UUID paymentId,
                                   @Valid @RequestBody(required = false) CaptureRequest request) {
        Payment payment = payments.require(paymentId);

        // No amount means capture everything the issuer approved.
        Money captureAmount = (request == null || request.amount() == null)
                ? payment.authorizedAmount()
                : Money.of(request.amount(), payment.amount().currency());

        return PaymentResponse.from(payments.capture(paymentId, captureAmount));
    }

    @PostMapping("/{paymentId}/refund")
    public RefundResponse refund(@PathVariable UUID paymentId,
                                 @Valid @RequestBody RefundRequest request) {
        Money amount = Money.of(request.amount(), payments.require(paymentId).amount().currency());
        return RefundResponse.from(payments.refund(paymentId, amount, request.reason()));
    }

    @PostMapping("/{paymentId}/reverse")
    public PaymentResponse reverse(@PathVariable UUID paymentId,
                                   @Valid @RequestBody ReverseRequest request) {
        return PaymentResponse.from(payments.reverse(paymentId, request.reason()));
    }
}
