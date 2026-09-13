package com.nexuspay.account.api;

import com.nexuspay.account.domain.Account;
import com.nexuspay.account.domain.AccountStatus;
import com.nexuspay.account.domain.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record CreateAccountRequest(
            @NotNull UUID customerId,
            @NotNull AccountType accountType,
            @NotNull @DecimalMin("0") BigDecimal openingBalance,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code") String currency) {
    }

    public record AccountResponse(
            UUID accountId,
            UUID customerId,
            AccountType accountType,
            BigDecimal balance,
            String currency,
            AccountStatus status,
            Instant createdAt) {

        public static AccountResponse from(Account account) {
            return new AccountResponse(
                    account.accountId(),
                    account.customerId(),
                    account.accountType(),
                    account.balance().amount(),
                    account.currency().getCurrencyCode(),
                    account.status(),
                    account.createdAt());
        }
    }
}
