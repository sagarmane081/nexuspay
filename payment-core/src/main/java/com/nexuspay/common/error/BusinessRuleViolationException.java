package com.nexuspay.common.error;

/**
 * A request that is well-formed but not permitted by the business rules —
 * refunding more than was captured, using a blocked card, exceeding a limit.
 */
public class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    public BusinessRuleViolationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
