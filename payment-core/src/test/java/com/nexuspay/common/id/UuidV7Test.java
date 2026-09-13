package com.nexuspay.common.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UuidV7Test {

    @Test
    @DisplayName("carries version 7 and the RFC 9562 variant")
    void hasCorrectVersionAndVariant() {
        UUID id = UuidV7.generate();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("embeds the generation timestamp")
    void embedsTimestamp() {
        long millis = 1_789_000_000_000L;

        assertThat(UuidV7.timestampOf(UuidV7.generate(millis))).isEqualTo(millis);
    }

    @Test
    @DisplayName("sorts in creation order — the whole reason for choosing v7")
    void sortsChronologically() {
        long base = 1_789_000_000_000L;

        List<UUID> generated = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            generated.add(UuidV7.generate(base + i));
        }

        List<UUID> sortedLexicographically = generated.stream()
                .sorted((a, b) -> a.toString().compareTo(b.toString()))
                .toList();

        assertThat(sortedLexicographically)
                .as("string ordering must match generation order, which is what lets "
                        + "an index append rather than scatter")
                .isEqualTo(generated);
    }

    @Test
    @DisplayName("does not collide within the same millisecond")
    void uniqueWithinOneMillisecond() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            ids.add(UuidV7.generate(1_789_000_000_000L));
        }

        assertThat(ids).hasSize(50_000);
    }

    @Test
    @DisplayName("refuses to read a timestamp out of a non-v7 UUID")
    void rejectsWrongVersion() {
        assertThatThrownBy(() -> UuidV7.timestampOf(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a version 7 UUID");
    }
}
