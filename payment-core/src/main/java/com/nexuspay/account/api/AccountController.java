package com.nexuspay.account.api;

import com.nexuspay.account.api.AccountDtos.AccountResponse;
import com.nexuspay.account.api.AccountDtos.CreateAccountRequest;
import com.nexuspay.account.application.AccountService;
import com.nexuspay.common.money.Money;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.nexuspay.common.money.Currencies;
import java.util.Currency;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request,
                                                  UriComponentsBuilder uri) {
        // Money's constructor rejects a scale the currency cannot represent,
        // so ¥100.50 fails here rather than at the database.
        Money opening = Money.of(request.openingBalance(), Currencies.parse(request.currency()));

        AccountResponse body = AccountResponse.from(
                accounts.open(request.customerId(), request.accountType(), opening));

        return ResponseEntity
                .created(uri.path("/api/v1/accounts/{id}").build(body.accountId()))
                .body(body);
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable UUID accountId) {
        return AccountResponse.from(accounts.require(accountId));
    }
}
