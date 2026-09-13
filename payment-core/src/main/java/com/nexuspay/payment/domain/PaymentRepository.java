package com.nexuspay.payment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Takes a row-level write lock (SELECT ... FOR UPDATE).
     * <p>
     * Required for refunds. The cumulative check reads refundedAmount, adds the
     * new refund and compares against capturedAmount. Two concurrent refunds
     * without this lock both read the same starting value, both conclude they
     * fit, and together they exceed what was captured. The database CHECK
     * constraint would catch the overflow, but as an opaque error after the
     * fact; the lock makes the race impossible in the first place.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paymentId = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    List<Payment> findByCardId(UUID cardId);
}
