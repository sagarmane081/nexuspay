package com.nexuspay.account.application;

import com.nexuspay.account.domain.Account;
import com.nexuspay.account.domain.AccountRepository;
import com.nexuspay.account.domain.AccountType;
import com.nexuspay.common.error.EntityNotFoundException;
import com.nexuspay.common.money.Money;
import com.nexuspay.customer.application.CustomerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerService customers;
    private final Clock clock;

    public AccountService(AccountRepository accounts, CustomerService customers, Clock clock) {
        this.accounts = accounts;
        this.customers = customers;
        this.clock = clock;
    }

    @Transactional
    public Account open(UUID customerId, AccountType accountType, Money openingBalance) {
        // Throws if the customer does not exist, so the FK violation never
        // reaches the database as an opaque constraint error.
        customers.require(customerId);
        return accounts.save(Account.open(customerId, accountType, openingBalance, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Account require(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account", accountId));
    }
}
