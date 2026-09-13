package com.nexuspay.common.idempotency;

import com.nexuspay.common.id.UuidV7;
import com.nexuspay.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord extends AssignedIdEntity {

    public enum Status {
        /** Claimed by a request that has not finished yet. */
        IN_PROGRESS,
        /** Finished; the stored response is what a retry receives. */
        COMPLETED
    }

    @Id
    @Column(name = "idempotency_key_id", nullable = false, updatable = false)
    private UUID idempotencyKeyId;

    @Column(name = "key_value", nullable = false, updatable = false, length = 255)
    private String keyValue;

    @Column(name = "endpoint", nullable = false, updatable = false, length = 100)
    private String endpoint;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", length = 8000)
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    private IdempotencyRecord(UUID id, String keyValue, String endpoint, String requestHash,
                              Instant now, Instant expiresAt) {
        this.idempotencyKeyId = id;
        this.keyValue = keyValue;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
        this.status = Status.IN_PROGRESS;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public static IdempotencyRecord claim(String keyValue, String endpoint, String requestHash,
                                          Instant now, Instant expiresAt) {
        return new IdempotencyRecord(UuidV7.generate(), keyValue, endpoint, requestHash, now, expiresAt);
    }

    public void complete(int responseStatus, String responseBody, Instant now) {
        this.status = Status.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.completedAt = now;
    }

    public boolean matches(String otherRequestHash) {
        return requestHash.equals(otherRequestHash);
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    @Override
    public UUID getId() {
        return idempotencyKeyId;
    }

    public String keyValue() {
        return keyValue;
    }

    public String endpoint() {
        return endpoint;
    }

    public Status status() {
        return status;
    }

    public Integer responseStatus() {
        return responseStatus;
    }

    public String responseBody() {
        return responseBody;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
