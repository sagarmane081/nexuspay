package com.nexuspay.payment.application;

import com.nexuspay.common.money.Money;
import com.nexuspay.payment.domain.IssuerGateway;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Stands in for a real issuer until Phase 4.1 replaces it with ISO 8583
 * messaging.
 * <p>
 * It approves everything. That is deliberate rather than lazy: decline logic
 * belongs to the risk engine in Phase 3.1, and inventing arbitrary rules here
 * would mean writing tests against behaviour we intend to delete.
 */
@Component
public class SimulatedIssuer implements IssuerGateway {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    @Override
    public IssuerDecision requestAuthorization(String cardToken, Money amount, String mcc) {
        return IssuerDecision.approve(generateAuthCode());
    }

    /** Six alphanumeric characters, the shape issuers actually return. */
    private static String generateAuthCode() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
