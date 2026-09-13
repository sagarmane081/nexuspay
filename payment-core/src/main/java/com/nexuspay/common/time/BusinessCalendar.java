package com.nexuspay.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Translates an instant into the business date its batch belongs to.
 * <p>
 * Timestamps are stored in UTC; business dates are calculated in Asia/Tokyo.
 * These are genuinely different things, and conflating them is one of the
 * easiest ways to put a payment in the wrong settlement batch. A capture at
 * 21:50 JST and one at 22:10 JST are ten minutes apart and belong to
 * <em>different</em> business dates.
 * <p>
 * Deliberately free of Spring: the cut-off arrives through the constructor so
 * this can be unit-tested without a context, and so
 * {@code docs/business-requirements.md}'s [DECISION] value stays configuration
 * rather than a literal buried in code.
 */
public final class BusinessCalendar {

    public static final ZoneId SETTLEMENT_ZONE = ZoneId.of("Asia/Tokyo");

    private final LocalTime cutOff;

    public BusinessCalendar(LocalTime cutOff) {
        this.cutOff = Objects.requireNonNull(cutOff, "cutOff must not be null");
    }

    /**
     * The business date an instant belongs to. Anything at or after the cut-off
     * rolls into the next day's batch.
     */
    public LocalDate businessDateOf(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        ZonedDateTime tokyo = instant.atZone(SETTLEMENT_ZONE);
        return tokyo.toLocalTime().isBefore(cutOff)
                ? tokyo.toLocalDate()
                : tokyo.toLocalDate().plusDays(1);
    }

    /** True when the instant still falls inside the current business date's window. */
    public boolean isBeforeCutOff(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return instant.atZone(SETTLEMENT_ZONE).toLocalTime().isBefore(cutOff);
    }

    public LocalTime cutOff() {
        return cutOff;
    }
}
