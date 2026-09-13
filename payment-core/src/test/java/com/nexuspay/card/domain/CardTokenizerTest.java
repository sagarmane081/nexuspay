package com.nexuspay.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTokenizerTest {

    /** A well-known test PAN. Never a real card number. */
    private static final String TEST_PAN = "4111111111111111";

    private final CardTokenizer tokenizer = new CardTokenizer("test-secret");

    @Test
    @DisplayName("masks as first six plus last four")
    void masksCorrectly() {
        assertThat(CardTokenizer.mask(TEST_PAN)).isEqualTo("411111******1111");
    }

    @Test
    @DisplayName("the masked form satisfies the database CHECK constraint")
    void maskMatchesTheDatabaseConstraint() {
        // card_pan_is_masked in V2__parties.sql
        String constraint = "^[0-9]{6}\\*{4,9}[0-9]{4}$";

        assertThat(CardTokenizer.mask("4111111111111111")).matches(constraint); // 16, Visa
        assertThat(CardTokenizer.mask("378282246310005")).matches(constraint);  // 15, Amex
        assertThat(CardTokenizer.mask("3530111333300000")).matches(constraint); // 16, JCB
        assertThat(CardTokenizer.mask("4111111111111111111")).matches(constraint); // 19, max
        assertThat(CardTokenizer.mask("41111111111111")).matches(constraint);   // 14, min
    }

    @Test
    @DisplayName("the same PAN always yields the same token")
    void tokenIsDeterministic() {
        assertThat(tokenizer.tokenFor(TEST_PAN)).isEqualTo(tokenizer.tokenFor(TEST_PAN));
    }

    @Test
    @DisplayName("different PANs yield different tokens")
    void differentPansDifferentTokens() {
        assertThat(tokenizer.tokenFor(TEST_PAN))
                .isNotEqualTo(tokenizer.tokenFor("4111111111111129"));
    }

    @Test
    @DisplayName("the token is keyed, so a different secret produces a different token")
    void tokenDependsOnTheSecret() {
        assertThat(tokenizer.tokenFor(TEST_PAN))
                .as("an unkeyed hash could be reversed with a precomputed table of all PANs")
                .isNotEqualTo(new CardTokenizer("a-different-secret").tokenFor(TEST_PAN));
    }

    @Test
    @DisplayName("the token fits the card_token column and contains no PAN digits")
    void tokenShapeIsSafe() {
        String token = tokenizer.tokenFor(TEST_PAN);

        assertThat(token).startsWith("tok_").hasSizeLessThanOrEqualTo(64);
        assertThat(token).doesNotContain(TEST_PAN);
        assertThat(token).doesNotContain(TEST_PAN.substring(0, 6));
    }

    @Test
    @DisplayName("a rejected PAN is never echoed in the error message")
    void errorMessageNeverLeaksThePan() {
        String badPan = "4111111111111111111111";

        assertThatThrownBy(() -> tokenizer.tokenFor(badPan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PAN must be 14 to 19 digits")
                .hasMessageNotContaining(badPan);
    }

    @Test
    @DisplayName("non-numeric and wrong-length input is refused")
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> tokenizer.tokenFor("4111-1111-1111-1111"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokenizer.tokenFor("41111"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokenizer.tokenFor(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank secret is refused at construction")
    void requiresASecret() {
        assertThatThrownBy(() -> new CardTokenizer("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret must not be blank");
    }
}
