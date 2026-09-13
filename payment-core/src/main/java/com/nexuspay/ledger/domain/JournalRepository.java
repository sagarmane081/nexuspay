package com.nexuspay.ledger.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JournalRepository extends JpaRepository<Journal, UUID> {

    List<Journal> findByPaymentId(UUID paymentId);

    List<Journal> findByCorrelationId(UUID correlationId);
}
