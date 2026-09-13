package com.nexuspay.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentAuthorizationRepository extends JpaRepository<PaymentAuthorization, UUID> {

    java.util.Optional<PaymentAuthorization> findByPaymentId(UUID paymentId);
}
