package com.nexuspay.merchant.api;

import com.nexuspay.merchant.domain.Merchant;
import com.nexuspay.merchant.domain.MerchantStatus;
import com.nexuspay.merchant.domain.Terminal;
import com.nexuspay.merchant.domain.TerminalStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class MerchantDtos {

    private MerchantDtos() {
    }

    public record OnboardMerchantRequest(
            @NotBlank @Size(max = 200) String legalName,
            @NotNull @Pattern(regexp = "^[0-9]{4}$", message = "must be a 4-digit MCC") String mcc,
            @NotNull @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be an ISO 3166-1 alpha-2 code") String country,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code") String settlementCurrency) {
    }

    public record MerchantResponse(
            UUID merchantId,
            String legalName,
            String mcc,
            String country,
            String settlementCurrency,
            MerchantStatus status,
            Instant createdAt) {

        public static MerchantResponse from(Merchant merchant) {
            return new MerchantResponse(
                    merchant.merchantId(),
                    merchant.legalName(),
                    merchant.mcc(),
                    merchant.country(),
                    merchant.settlementCurrency().getCurrencyCode(),
                    merchant.status(),
                    merchant.createdAt());
        }
    }

    public record RegisterTerminalRequest(
            @NotBlank @Size(max = 50) String terminalRef) {
    }

    public record TerminalResponse(
            UUID terminalId,
            UUID merchantId,
            String terminalRef,
            TerminalStatus status,
            Instant createdAt) {

        public static TerminalResponse from(Terminal terminal) {
            return new TerminalResponse(
                    terminal.terminalId(),
                    terminal.merchantId(),
                    terminal.terminalRef(),
                    terminal.status(),
                    terminal.createdAt());
        }
    }
}
