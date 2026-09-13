package com.nexuspay.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexuspay.common.config.NexusPayProperties;
import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes a money-moving operation safe to retry.
 * <p>
 * A client sends POST /payments and the response is lost — a timeout, a dropped
 * connection, a phone entering a tunnel. The client cannot distinguish "it never
 * happened" from "it happened and I didn't hear", so its only safe move is to
 * retry. This class makes that retry harmless.
 * <p>
 * The mechanism rests on a <b>unique index</b>, not on checking whether a row
 * exists first. "Look, then insert" is the classic broken version: two
 * concurrent requests both look, both see nothing, and both proceed. Letting the
 * database reject the second insert is what makes the race impossible rather
 * than merely unlikely.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;
    private final ObjectMapper json;
    private final NexusPayProperties properties;
    private final Clock clock;

    public IdempotencyService(IdempotencyStore store, ObjectMapper json,
                              NexusPayProperties properties, Clock clock) {
        this.store = store;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs {@code operation} at most once for a given key and endpoint.
     *
     * @param key       the client's {@code Idempotency-Key}
     * @param endpoint  scopes the key, so the same key used on two endpoints is two operations
     * @param request   the request payload, hashed to detect reuse with a different body
     * @param type      the response type, needed to rebuild a stored response
     * @param operation the work to perform exactly once
     */
    public <T> T execute(String key, String endpoint, Object request, Class<T> type, Supplier<T> operation) {
        // Validated here rather than as a @NotBlank on the controller
        // parameter: parameter-level constraints are silently ignored without
        // class-level @Validated, which would look like protection while
        // providing none.
        if (key == null || key.isBlank()) {
            throw new BusinessRuleViolationException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must not be blank");
        }
        if (key.length() > 255) {
            throw new BusinessRuleViolationException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be at most 255 characters");
        }

        String requestHash = hash(request);
        Instant now = clock.instant();

        try {
            store.claim(key, endpoint, requestHash, now, now.plus(properties.idempotency().retention()));
        } catch (DataIntegrityViolationException duplicate) {
            // Either an honest retry or a lost race. Both arrive here.
            return replay(key, endpoint, requestHash, type);
        }

        try {
            T result = operation.get();
            store.complete(key, endpoint, 200, serialize(result), clock.instant());
            return result;
        } catch (RuntimeException e) {
            // The operation was transactional, so nothing committed. Burning the
            // key would mean the client could never retry successfully even
            // after the cause was fixed. Release it and let the error surface.
            store.release(key, endpoint);
            throw e;
        }
    }

    private <T> T replay(String key, String endpoint, String requestHash, Class<T> type) {
        Optional<IdempotencyRecord> found = store.find(key, endpoint);

        if (found.isEmpty()) {
            // Vanishingly rare: a sibling released the key between our failed
            // insert and this read. Report a conflict rather than guessing.
            throw new BusinessRuleViolationException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                    "another request with this Idempotency-Key is in progress; retry shortly");
        }

        IdempotencyRecord record = found.get();

        if (!record.matches(requestHash)) {
            // The dangerous case. Without this check the client receives a
            // successful response describing a payment it never asked for.
            throw new BusinessRuleViolationException(ErrorCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY,
                    "Idempotency-Key '%s' was already used on %s with a different request body"
                            .formatted(key, endpoint));
        }

        if (!record.isCompleted()) {
            // A sibling is mid-flight. Blocking would hold a connection for as
            // long as the other request takes; 409 tells the client to retry,
            // and that retry finds the completed record.
            throw new BusinessRuleViolationException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                    "another request with this Idempotency-Key is still in progress; retry shortly");
        }

        log.debug("replaying stored response for key={} endpoint={}", key, endpoint);
        return deserialize(record.responseBody(), type);
    }

    /**
     * Hashes the deserialized request rather than the raw bytes, so the same
     * logical request re-sent with different whitespace or field ordering is
     * still recognised as the same request.
     */
    private String hash(Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(request)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("could not hash request for idempotency", e);
        }
    }

    private String serialize(Object result) {
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not store idempotent response", e);
        }
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return json.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not replay stored response", e);
        }
    }
}
