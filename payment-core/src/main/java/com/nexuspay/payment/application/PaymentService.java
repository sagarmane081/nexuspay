package com.nexuspay.payment.application;

import com.nexuspay.card.application.CardService;
import com.nexuspay.card.domain.Card;
import com.nexuspay.common.api.CorrelationIdFilter;
import com.nexuspay.common.config.NexusPayProperties;
import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.error.EntityNotFoundException;
import com.nexuspay.common.id.UuidV7;
import com.nexuspay.common.money.Money;
import com.nexuspay.common.time.BusinessCalendar;
import com.nexuspay.merchant.application.MerchantService;
import com.nexuspay.merchant.domain.Merchant;
import com.nexuspay.payment.domain.Capture;
import com.nexuspay.payment.domain.CaptureRepository;
import com.nexuspay.payment.domain.IssuerGateway;
import com.nexuspay.payment.domain.Payment;
import com.nexuspay.payment.domain.PaymentAuthorization;
import com.nexuspay.payment.domain.PaymentAuthorizationRepository;
import com.nexuspay.payment.domain.PaymentRepository;
import com.nexuspay.payment.domain.Refund;
import com.nexuspay.payment.domain.RefundRepository;
import com.nexuspay.payment.domain.Reversal;
import com.nexuspay.payment.domain.ReversalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates the payment lifecycle. The rules live in {@link Payment}; this
 * class sequences the steps, talks to the issuer and persists the records.
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentAuthorizationRepository authorizations;
    private final CaptureRepository captures;
    private final RefundRepository refunds;
    private final ReversalRepository reversals;
    private final CardService cards;
    private final MerchantService merchants;
    private final IssuerGateway issuer;
    private final BusinessCalendar calendar;
    private final NexusPayProperties properties;
    private final Clock clock;

    public PaymentService(PaymentRepository payments,
                          PaymentAuthorizationRepository authorizations,
                          CaptureRepository captures,
                          RefundRepository refunds,
                          ReversalRepository reversals,
                          CardService cards,
                          MerchantService merchants,
                          IssuerGateway issuer,
                          BusinessCalendar calendar,
                          NexusPayProperties properties,
                          Clock clock) {
        this.payments = payments;
        this.authorizations = authorizations;
        this.captures = captures;
        this.refunds = refunds;
        this.reversals = reversals;
        this.cards = cards;
        this.merchants = merchants;
        this.issuer = issuer;
        this.calendar = calendar;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs a payment from arrival to an issuer decision:
     * RECEIVED, VALIDATED, RISK_CHECK, then AUTHORIZED or DECLINED.
     * <p>
     * Every step is a real state transition rather than a variable, so the
     * state machine rejects any attempt to skip one.
     */
    @Transactional
    public Payment authorize(UUID cardId, UUID merchantId, UUID terminalId, Money amount) {
        Instant now = clock.instant();

        Card card = cards.require(cardId);
        Merchant merchant = merchants.require(merchantId);
        if (terminalId != null) {
            merchants.requireTerminal(terminalId);
        }

        Payment payment = Payment.initiate(
                currentCorrelationId(), merchantId, terminalId, cardId, amount,
                merchant.mcc(), calendar.businessDateOf(now), now);
        payments.save(payment);

        payment.validate();

        // The Phase 1.3 rule finally gets used: a blocked, cancelled or expired
        // card cannot pay. Checked against the business date, not the wall
        // clock, so a payment is judged on the day it belongs to.
        try {
            card.assertUsableOn(payment.businessDate());
            merchant.assertCanAcceptPayments();
        } catch (BusinessRuleViolationException e) {
            payment.decline(e.getMessage());
            return payment;
        }

        payment.beginRiskCheck();
        // Real rules arrive in Phase 3.1. Recording the decision now means the
        // column is populated and the audit trail is complete from the start.
        payment.recordRiskDecision("APPROVE", 0);

        IssuerGateway.IssuerDecision decision =
                issuer.requestAuthorization(card.cardToken(), amount, merchant.mcc());

        Instant expiresAt = now.plus(properties.authorization().retailValidity());

        if (!decision.approved()) {
            authorizations.save(PaymentAuthorization.declined(
                    payment.paymentId(), amount, decision.responseCode(), expiresAt, now));
            payment.decline("issuer declined with response code " + decision.responseCode());
            return payment;
        }

        authorizations.save(PaymentAuthorization.approved(
                payment.paymentId(), amount, decision.authCode(), decision.responseCode(), expiresAt, now));
        payment.authorize(amount, decision.authCode(), now);
        return payment;
    }

    @Transactional
    public Payment capture(UUID paymentId, Money captureAmount) {
        Instant now = clock.instant();
        Payment payment = requireForUpdate(paymentId);

        PaymentAuthorization authorization = authorizations.findByPaymentId(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("PaymentAuthorization", paymentId));

        if (now.isAfter(authorization.expiresAt())) {
            throw new BusinessRuleViolationException(
                    "authorization for payment %s expired at %s".formatted(paymentId, authorization.expiresAt()));
        }

        payment.capture(captureAmount, now);
        captures.save(Capture.of(authorization.authorizationId(), paymentId, captureAmount, now));
        return payment;
    }

    /**
     * Refunds are the one operation with a genuine concurrency hazard, so the
     * payment row is locked for the duration. See
     * {@link PaymentRepository#findByIdForUpdate}.
     */
    @Transactional
    public Refund refund(UUID paymentId, Money refundAmount, String reason) {
        Instant now = clock.instant();
        Payment payment = requireForUpdate(paymentId);

        payment.refund(refundAmount);
        return refunds.save(Refund.of(paymentId, refundAmount, reason, now));
    }

    @Transactional
    public Payment reverse(UUID paymentId, String reason) {
        Instant now = clock.instant();
        Payment payment = requireForUpdate(paymentId);

        PaymentAuthorization authorization = authorizations.findByPaymentId(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("PaymentAuthorization", paymentId));

        Money reversedAmount = payment.capturedAmount() != null
                ? payment.capturedAmount()
                : payment.authorizedAmount();

        payment.reverse();
        reversals.save(Reversal.of(paymentId, authorization.authorizationId(), reversedAmount, reason, now));
        return payment;
    }

    // ------------------------------------------------------------------
    // Driven by clearing and settlement in Phase 4. Not exposed over the API:
    // these are batch outcomes, not things a caller asks for.
    // ------------------------------------------------------------------

    @Transactional
    public Payment markCleared(UUID paymentId) {
        Payment payment = requireForUpdate(paymentId);
        payment.markCleared();
        return payment;
    }

    @Transactional
    public Payment markSettled(UUID paymentId) {
        Payment payment = requireForUpdate(paymentId);
        payment.markSettled();
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment require(UUID paymentId) {
        return payments.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment", paymentId));
    }

    private Payment requireForUpdate(UUID paymentId) {
        return payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment", paymentId));
    }

    /**
     * The correlation ID minted by the filter at the edge, so the payment row
     * carries the same ID that appears in the logs and, later, in the export
     * files and the data hub.
     */
    private static UUID currentCorrelationId() {
        String current = CorrelationIdFilter.current();
        if (current == null) {
            return UuidV7.generate();
        }
        try {
            return UUID.fromString(current);
        } catch (IllegalArgumentException e) {
            // A caller may supply any string as a trace header; keep theirs in
            // the logs but store a well-formed UUID in the column.
            return UuidV7.generate();
        }
    }
}
