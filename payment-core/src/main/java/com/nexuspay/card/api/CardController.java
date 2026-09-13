package com.nexuspay.card.api;

import com.nexuspay.card.api.CardDtos.CardResponse;
import com.nexuspay.card.api.CardDtos.IssueCardRequest;
import com.nexuspay.card.application.CardService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/cards")
public class CardController {

    private final CardService cards;

    public CardController(CardService cards) {
        this.cards = cards;
    }

    @PostMapping
    public ResponseEntity<CardResponse> issue(@Valid @RequestBody IssueCardRequest request,
                                              UriComponentsBuilder uri) {
        CardResponse body = CardResponse.from(cards.issue(
                request.accountId(), request.pan(), request.cardScheme(),
                request.expiryMonth(), request.expiryYear()));

        return ResponseEntity
                .created(uri.path("/api/v1/cards/{id}").build(body.cardId()))
                .body(body);
    }

    @GetMapping("/{cardId}")
    public CardResponse get(@PathVariable UUID cardId) {
        return CardResponse.from(cards.require(cardId));
    }

    @PostMapping("/{cardId}/block")
    public CardResponse block(@PathVariable UUID cardId) {
        return CardResponse.from(cards.block(cardId));
    }

    @PostMapping("/{cardId}/unblock")
    public CardResponse unblock(@PathVariable UUID cardId) {
        return CardResponse.from(cards.unblock(cardId));
    }

    @PostMapping("/{cardId}/cancel")
    public CardResponse cancel(@PathVariable UUID cardId) {
        return CardResponse.from(cards.cancel(cardId));
    }
}
