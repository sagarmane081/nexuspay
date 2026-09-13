package com.nexuspay.merchant.domain;

import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.error.ErrorCode;
import com.nexuspay.common.id.UuidV7;
import com.nexuspay.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "merchant")
public class Merchant extends AssignedIdEntity {

    @Id
    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Column(name = "mcc", nullable = false, length = 4)
    private String mcc;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "settlement_currency", nullable = false, length = 3)
    private String settlementCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MerchantStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Merchant() {
        // for JPA
    }

    private Merchant(UUID merchantId, String legalName, String mcc, String country,
                     Currency settlementCurrency, Instant now) {
        this.merchantId = merchantId;
        this.legalName = legalName;
        this.mcc = mcc;
        this.country = country.toUpperCase();
        this.settlementCurrency = settlementCurrency.getCurrencyCode();
        this.status = MerchantStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Merchant onboard(String legalName, String mcc, String country,
                                   Currency settlementCurrency, Instant now) {
        return new Merchant(UuidV7.generate(), legalName, mcc, country, settlementCurrency, now);
    }

    public void assertCanAcceptPayments() {
        if (status != MerchantStatus.ACTIVE) {
            throw new BusinessRuleViolationException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "merchant %s is %s and cannot accept payments".formatted(merchantId, status));
        }
    }

    public void suspend(Instant now) {
        this.status = MerchantStatus.SUSPENDED;
        this.updatedAt = now;
    }

    @Override
    public UUID getId() {
        return merchantId;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String legalName() {
        return legalName;
    }

    public String mcc() {
        return mcc;
    }

    public String country() {
        return country;
    }

    public Currency settlementCurrency() {
        return Currency.getInstance(settlementCurrency);
    }

    public MerchantStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
