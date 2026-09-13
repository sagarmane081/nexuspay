package com.nexuspay.merchant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TerminalRepository extends JpaRepository<Terminal, UUID> {

    List<Terminal> findByMerchantId(UUID merchantId);

    boolean existsByMerchantIdAndTerminalRef(UUID merchantId, String terminalRef);
}
