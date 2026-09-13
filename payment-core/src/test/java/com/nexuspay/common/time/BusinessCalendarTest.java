package com.nexuspay.common.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessCalendarTest {

    private final BusinessCalendar calendar = new BusinessCalendar(LocalTime.of(22, 0));

    /** Tokyo is UTC+9 year round — Japan observes no daylight saving. */
    private static Instant tokyo(int year, int month, int day, int hour, int minute) {
        return LocalDate.of(year, month, day)
                .atTime(hour, minute)
                .atZone(BusinessCalendar.SETTLEMENT_ZONE)
                .toInstant();
    }

    @Test
    @DisplayName("21:50 and 22:10 are ten minutes apart and on different business dates")
    void theCutOffBoundary() {
        assertThat(calendar.businessDateOf(tokyo(2026, 9, 14, 21, 50)))
                .isEqualTo(LocalDate.of(2026, 9, 14));

        assertThat(calendar.businessDateOf(tokyo(2026, 9, 14, 22, 10)))
                .isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("exactly at the cut-off belongs to the next business date")
    void exactlyAtCutOffRollsForward() {
        assertThat(calendar.businessDateOf(tokyo(2026, 9, 14, 22, 0)))
                .isEqualTo(LocalDate.of(2026, 9, 15));

        assertThat(calendar.businessDateOf(tokyo(2026, 9, 14, 21, 59)))
                .isEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test
    @DisplayName("business date is computed in Tokyo, not UTC")
    void businessDateIsNotTheUtcDate() {
        // 2026-09-14T20:00Z is already 2026-09-15 05:00 in Tokyo.
        Instant instant = Instant.parse("2026-09-14T20:00:00Z");

        assertThat(instant.toString()).startsWith("2026-09-14");
        assertThat(calendar.businessDateOf(instant))
                .as("a UTC date of the 14th can be business date the 15th")
                .isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("just after midnight Tokyo still belongs to the same calendar day's batch")
    void justAfterMidnight() {
        assertThat(calendar.businessDateOf(tokyo(2026, 9, 15, 0, 5)))
                .isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("isBeforeCutOff agrees with businessDateOf")
    void isBeforeCutOffIsConsistent() {
        Instant before = tokyo(2026, 9, 14, 21, 50);
        Instant after = tokyo(2026, 9, 14, 22, 10);

        assertThat(calendar.isBeforeCutOff(before)).isTrue();
        assertThat(calendar.isBeforeCutOff(after)).isFalse();

        assertThat(calendar.businessDateOf(before)).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(calendar.businessDateOf(after)).isNotEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test
    @DisplayName("a month boundary rolls the business date into the next month")
    void monthBoundary() {
        assertThat(calendar.businessDateOf(tokyo(2026, 9, 30, 23, 0)))
                .isEqualTo(LocalDate.of(2026, 10, 1));
    }
}
