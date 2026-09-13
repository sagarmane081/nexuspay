package com.nexuspay.card.application;

import com.nexuspay.account.application.AccountService;
import com.nexuspay.card.domain.Card;
import com.nexuspay.card.domain.CardRepository;
import com.nexuspay.card.domain.CardScheme;
import com.nexuspay.card.domain.CardTokenizer;
import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.error.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class CardService {

    private final CardRepository cards;
    private final AccountService accounts;
    private final CardTokenizer tokenizer;
    private final Clock clock;

    public CardService(CardRepository cards, AccountService accounts, CardTokenizer tokenizer, Clock clock) {
        this.cards = cards;
        this.accounts = accounts;
        this.tokenizer = tokenizer;
        this.clock = clock;
    }

    /**
     * The PAN exists only inside this method. It is converted to a token and a
     * masked form, and neither it nor anything derived from it beyond those two
     * values is stored or returned.
     */
    @Transactional
    public Card issue(UUID accountId, String pan, CardScheme scheme, int expiryMonth, int expiryYear) {
        accounts.require(accountId);

        String token = tokenizer.tokenFor(pan);
        cards.findByCardToken(token).ifPresent(existing -> {
            throw new BusinessRuleViolationException("this card is already registered as " + existing.cardId());
        });

        return cards.save(Card.issue(accountId, token, CardTokenizer.mask(pan),
                scheme, expiryMonth, expiryYear, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Card require(UUID cardId) {
        return cards.findById(cardId)
                .orElseThrow(() -> new EntityNotFoundException("Card", cardId));
    }

    @Transactional
    public Card block(UUID cardId) {
        Card card = require(cardId);
        card.block(clock.instant());
        return card;
    }

    @Transactional
    public Card unblock(UUID cardId) {
        Card card = require(cardId);
        card.unblock(clock.instant());
        return card;
    }

    @Transactional
    public Card cancel(UUID cardId) {
        Card card = require(cardId);
        card.cancel(clock.instant());
        return card;
    }
}
