package com.nexuspay.merchant.application;

import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.error.EntityNotFoundException;
import com.nexuspay.merchant.domain.Merchant;
import com.nexuspay.merchant.domain.MerchantRepository;
import com.nexuspay.merchant.domain.Terminal;
import com.nexuspay.merchant.domain.TerminalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@Service
public class MerchantService {

    private final MerchantRepository merchants;
    private final TerminalRepository terminals;
    private final Clock clock;

    public MerchantService(MerchantRepository merchants, TerminalRepository terminals, Clock clock) {
        this.merchants = merchants;
        this.terminals = terminals;
        this.clock = clock;
    }

    @Transactional
    public Merchant onboard(String legalName, String mcc, String country, Currency settlementCurrency) {
        return merchants.save(Merchant.onboard(legalName, mcc, country, settlementCurrency, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Merchant require(UUID merchantId) {
        return merchants.findById(merchantId)
                .orElseThrow(() -> new EntityNotFoundException("Merchant", merchantId));
    }

    @Transactional
    public Terminal registerTerminal(UUID merchantId, String terminalRef) {
        require(merchantId);
        if (terminals.existsByMerchantIdAndTerminalRef(merchantId, terminalRef)) {
            throw new BusinessRuleViolationException(
                    "terminal %s is already registered for this merchant".formatted(terminalRef));
        }
        return terminals.save(Terminal.register(merchantId, terminalRef, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Terminal requireTerminal(UUID terminalId) {
        return terminals.findById(terminalId)
                .orElseThrow(() -> new EntityNotFoundException("Terminal", terminalId));
    }

    @Transactional(readOnly = true)
    public List<Terminal> terminalsOf(UUID merchantId) {
        return terminals.findByMerchantId(merchantId);
    }
}
