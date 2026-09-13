package com.nexuspay.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    java.util.List<Refund> findByPaymentId(UUID paymentId);
}
