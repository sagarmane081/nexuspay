package com.nexuspay.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates UUID version 7 identifiers (RFC 9562): a 48-bit millisecond
 * timestamp followed by random bits.
 * <p>
 * Why not {@code UUID.randomUUID()} (version 4)? Because v7 sorts by creation
 * time. That matters in two places:
 * <ul>
 *   <li><b>Index locality.</b> Random primary keys scatter inserts across the
 *       whole B-tree; time-ordered keys append to the right-hand edge, which
 *       keeps the working set small on a table that only ever grows.</li>
 *   <li><b>Downstream file pruning.</b> {@code docs/data-contracts.md} commits
 *       to v7 so Delta can skip files by min/max ID range.</li>
 * </ul>
 * PostgreSQL 16 has no native {@code uuidv7()}, so IDs are generated here and
 * the database columns carry no default. One generator, one behaviour.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    /** Visible for testing with a fixed clock. */
    static UUID generate(long unixMillis) {
        // Most significant bits: 48-bit timestamp | 4-bit version (7) | 12 random bits
        long msb = (unixMillis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L;                          // version 7
        msb |= RANDOM.nextInt(0x1000);           // rand_a

        // Least significant bits: 2-bit variant (binary 10) | 62 random bits
        long lsb = RANDOM.nextLong();
        lsb &= 0x3FFF_FFFF_FFFF_FFFFL;           // clear the top two bits
        lsb |= 0x8000_0000_0000_0000L;           // RFC 9562 variant

        return new UUID(msb, lsb);
    }

    /** The millisecond timestamp embedded in a v7 UUID. */
    public static long timestampOf(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("not a version 7 UUID: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
