package com.nexuspay.card.api;

import com.nexuspay.card.domain.Card;
import com.nexuspay.card.domain.CardScheme;
import com.nexuspay.card.domain.CardStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public final class CardDtos {

    private CardDtos() {
    }

    /**
     * The only place a PAN appears in the system, and it never leaves this
     * object — {@code CardService.issue} converts it to a token and a mask and
     * discards it.
     */
    public record IssueCardRequest(
            @NotNull UUID accountId,
            @NotNull @Pattern(regexp = "^[0-9]{14,19}$", message = "must be 14 to 19 digits") String pan,
            @NotNull CardScheme cardScheme,
            @NotNull @Min(1) @Max(12) Integer expiryMonth,
            @NotNull @Min(2000) @Max(2099) Integer expiryYear) {

        /**
         * Overridden because the generated one would print the PAN. Records are
         * routinely stringified into log lines, exception messages and
         * debugger output, and any one of those would put a card number
         * somewhere it must never be.
         */
        @Override
        public String toString() {
            return "IssueCardRequest[accountId=%s, pan=***REDACTED***, cardScheme=%s, expiry=%02d/%d]"
                    .formatted(accountId, cardScheme, expiryMonth, expiryYear);
        }
    }

    public record CardResponse(
            UUID cardId,
            UUID accountId,
            String panMasked,
            CardScheme cardScheme,
            int expiryMonth,
            int expiryYear,
            CardStatus status,
            Instant createdAt) {

        public static CardResponse from(Card card) {
            // cardToken is deliberately absent: it is an internal handle, and
            // exposing it would let a caller reference a card they never saw.
            return new CardResponse(
                    card.cardId(),
                    card.accountId(),
                    card.panMasked(),
                    card.cardScheme(),
                    card.expiryMonth(),
                    card.expiryYear(),
                    card.status(),
                    card.createdAt());
        }
    }
}
