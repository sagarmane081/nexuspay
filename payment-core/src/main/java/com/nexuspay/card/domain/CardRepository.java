package com.nexuspay.card.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CardRepository extends JpaRepository<Card, UUID> {

    Optional<Card> findByCardToken(String cardToken);

    List<Card> findByAccountId(UUID accountId);
}
