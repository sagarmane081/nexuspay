package com.nexuspay.common.idempotency;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * The transactional half of idempotency, deliberately a separate bean.
 * <p>
 * Spring's {@code @Transactional} works through a proxy, and a call from one
 * method of a class to another method of the <em>same</em> class never touches
 * that proxy. Had these methods lived alongside
 * {@link IdempotencyService#execute}, {@code REQUIRES_NEW} would have been
 * silently ignored: the claim would have joined the caller's transaction,
 * stayed invisible until commit, and two concurrent requests would each have
 * created a payment — the exact bug idempotency exists to prevent, hidden
 * behind an annotation that looks correct.
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRecordRepository records;

    public IdempotencyStore(IdempotencyRecordRepository records) {
        this.records = records;
    }

    /**
     * Inserts the claim and commits it immediately.
     * <p>
     * Throws {@link org.springframework.dao.DataIntegrityViolationException} if
     * the key is already claimed — that unique-index violation is the signal
     * that another request owns this key, and it is load-bearing rather than
     * an error to be avoided.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claim(String key, String endpoint, String requestHash, Instant now, Instant expiresAt) {
        records.saveAndFlush(IdempotencyRecord.claim(key, endpoint, requestHash, now, expiresAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, String endpoint, int responseStatus, String responseBody, Instant now) {
        records.findByKeyValueAndEndpoint(key, endpoint).ifPresent(record -> {
            record.complete(responseStatus, responseBody, now);
            records.save(record);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key, String endpoint) {
        records.findByKeyValueAndEndpoint(key, endpoint)
                .filter(record -> !record.isCompleted())
                .ifPresent(records::delete);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(String key, String endpoint) {
        return records.findByKeyValueAndEndpoint(key, endpoint);
    }
}
