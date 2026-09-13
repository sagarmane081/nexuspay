package com.nexuspay.common.error;

import java.util.UUID;

/** The referenced entity does not exist. */
public class EntityNotFoundException extends DomainException {

    public EntityNotFoundException(String entityType, UUID id) {
        super(ErrorCode.ENTITY_NOT_FOUND, "%s %s was not found".formatted(entityType, id));
    }

    public EntityNotFoundException(String entityType, String identifier) {
        super(ErrorCode.ENTITY_NOT_FOUND, "%s %s was not found".formatted(entityType, identifier));
    }
}
