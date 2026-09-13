package com.nexuspay.payment.api;

import com.nexuspay.payment.domain.Payment;
import com.nexuspay.payment.domain.PaymentStatus;
import com.nexuspay.payment.domain.Refund;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record CreatePaymentRequest(
            @NotNull UUID cardId,
            @NotNull UUID merchantId,
            UUID terminalId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code") String currency) {
    }

    /**
     * Amount is optional. Omitting it captures the full authorized amount;
     * supplying less is a partial capture.
     */
    public record CaptureRequest(
            @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
    }

    public record RefundRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @NotBlank @Size(max = 200) String reason) {
    }

    public record ReverseRequest(
            @NotBlank @Size(max = 200) String reason) {
    }

    public record PaymentResponse(
            UUID paymentId,
            UUID correlationId,
            UUID cardId,
            UUID merchantId,
            UUID terminalId,
            BigDecimal amount,
            BigDecimal authorizedAmount,
            BigDecimal capturedAmount,
            BigDecimal refundedAmount,
            String currency,
            PaymentStatus status,
            String authCode,
            String declineReason,
            LocalDate businessDate,
            Instant createdAt,
            Instant authorizedAt,
            Instant capturedAt) {

        public static PaymentResponse from(Payment payment) {
            return new PaymentResponse(
                    payment.paymentId(),
                    payment.correlationId(),
                    payment.cardId(),
                    payment.merchantId(),
                    payment.terminalId(),
                    payment.amount().amount(),
                    payment.authorizedAmount() == null ? null : payment.authorizedAmount().amount(),
                    payment.capturedAmount() == null ? null : payment.capturedAmount().amount(),
                    payment.refundedAmount().amount(),
                    payment.amount().currency().getCurrencyCode(),
                    payment.status(),
                    payment.authCode(),
                    payment.declineReason(),
                    payment.businessDate(),
                    payment.createdAt(),
                    payment.authorizedAt(),
                    payment.capturedAt());
        }
    }

    public record RefundResponse(
            UUID refundId,
            UUID paymentId,
            BigDecimal amount,
            String currency,
            String reason,
            Instant createdAt) {

        public static RefundResponse from(Refund refund) {
            return new RefundResponse(
                    refund.refundId(),
                    refund.paymentId(),
                    refund.amount().amount(),
                    refund.amount().currency().getCurrencyCode(),
                    refund.reason(),
                    refund.createdAt());
        }
    }
}
