package com.nexuspay.card.domain;

import java.util.Set;

/**
 * Card lifecycle. {@code EXPIRED} is the odd one out: it is reached by the
 * passage of time rather than by anyone issuing a command, so nothing
 * transitions <em>into</em> it on request.
 */
public enum CardStatus {

    ACTIVE,
    BLOCKED,
    EXPIRED,
    CANCELLED;

    private static final Set<CardStatus> FROM_ACTIVE = Set.of(BLOCKED, CANCELLED);
    private static final Set<CardStatus> FROM_BLOCKED = Set.of(ACTIVE, CANCELLED);
    private static final Set<CardStatus> FROM_EXPIRED = Set.of(CANCELLED);

    public boolean canTransitionTo(CardStatus target) {
        return switch (this) {
            case ACTIVE -> FROM_ACTIVE.contains(target);
            case BLOCKED -> FROM_BLOCKED.contains(target);
            case EXPIRED -> FROM_EXPIRED.contains(target);
            // Cancellation is deliberately irreversible. Re-activating a
            // cancelled card would resurrect a credential the holder believes
            // is dead — the card is gone, issue a new one.
            case CANCELLED -> false;
        };
    }

    public boolean isTerminal() {
        return this == CANCELLED;
    }
}
