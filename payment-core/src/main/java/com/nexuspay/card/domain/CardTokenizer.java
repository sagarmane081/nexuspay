package com.nexuspay.card.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Turns a PAN into a token and a masked display form.
 * <p>
 * The schema has no column for a raw PAN, so this is the only thing standing
 * between a card number and the database. Two deliberate choices:
 * <ul>
 *   <li><b>HMAC, not a plain hash.</b> A PAN has roughly 10^15 possibilities
 *       with a known checksum — small enough that an unsalted SHA-256 of every
 *       candidate can be precomputed. Keying the digest means a stolen token
 *       table cannot be reversed without also stealing the key.</li>
 *   <li><b>Deterministic, not random.</b> The same PAN always yields the same
 *       token, which lets the UNIQUE index on {@code card_token} do real work:
 *       registering the same card twice is rejected by the database.</li>
 * </ul>
 * Spring-free by design, so it can be tested without a context. The key arrives
 * through the constructor.
 */
public final class CardTokenizer {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public CardTokenizer(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("tokenization secret must not be blank");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String tokenFor(String pan) {
        requireValidPan(pan);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(pan.getBytes(StandardCharsets.UTF_8));
            // 20 of the 32 bytes is 160 bits — far beyond collision risk here,
            // and keeps the token inside card_token's 64 characters.
            byte[] truncated = new byte[20];
            System.arraycopy(digest, 0, truncated, 0, truncated.length);
            return "tok_" + HexFormat.of().formatHex(truncated);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /**
     * First six digits and last four, masked in between — the form permitted
     * everywhere in this system, and the only form the database will accept.
     */
    public static String mask(String pan) {
        requireValidPan(pan);
        return pan.substring(0, 6)
                + "*".repeat(pan.length() - 10)
                + pan.substring(pan.length() - 4);
    }

    private static void requireValidPan(String pan) {
        if (pan == null || !pan.matches("^[0-9]{14,19}$")) {
            // Never include the offending value: this message reaches logs.
            throw new IllegalArgumentException("PAN must be 14 to 19 digits");
        }
    }
}
