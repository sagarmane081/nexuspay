package com.nexuspay.common.error;

/**
 * Base class for failures the business cares about, as opposed to technical
 * faults. Carries a stable {@link ErrorCode} and nothing web-related.
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    protected DomainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected DomainException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
