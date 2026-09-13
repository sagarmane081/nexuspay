package com.nexuspay.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CaptureRepository extends JpaRepository<Capture, UUID> {
}
