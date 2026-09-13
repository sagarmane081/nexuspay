package com.nexuspay.payment.domain;

import com.nexuspay.common.money.Money;

/**
 * The issuer's authorization decision.
 * <p>
 * An interface rather than a concrete call because the real thing arrives in
 * Phase 4.1 as ISO 8583 messaging. Until then a simulator stands in, and the
 * payment flow is written against this contract so swapping it changes nothing
 * above.
 */
public interface IssuerGateway {

    IssuerDecision requestAuthorization(String cardToken, Money amount, String mcc);

    /**
     * @param approved     whether the issuer stands behind the amount
     * @param authCode     six-character approval code; null when declined
     * @param responseCode ISO 8583 field 39 — "00" approved, "05" do not honour,
     *                     "51" insufficient funds, "54" expired card
     */
    record IssuerDecision(boolean approved, String authCode, String responseCode) {

        public static IssuerDecision approve(String authCode) {
            return new IssuerDecision(true, authCode, "00");
        }

        public static IssuerDecision decline(String responseCode) {
            return new IssuerDecision(false, null, responseCode);
        }
    }
}
