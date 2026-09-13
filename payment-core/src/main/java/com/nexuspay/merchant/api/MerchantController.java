package com.nexuspay.merchant.api;

import com.nexuspay.merchant.api.MerchantDtos.MerchantResponse;
import com.nexuspay.merchant.api.MerchantDtos.OnboardMerchantRequest;
import com.nexuspay.merchant.api.MerchantDtos.RegisterTerminalRequest;
import com.nexuspay.merchant.api.MerchantDtos.TerminalResponse;
import com.nexuspay.merchant.application.MerchantService;
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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/merchants")
public class MerchantController {

    private final MerchantService merchants;

    public MerchantController(MerchantService merchants) {
        this.merchants = merchants;
    }

    @PostMapping
    public ResponseEntity<MerchantResponse> onboard(@Valid @RequestBody OnboardMerchantRequest request,
                                                    UriComponentsBuilder uri) {
        MerchantResponse body = MerchantResponse.from(merchants.onboard(
                request.legalName(), request.mcc(), request.country(),
                Currencies.parse(request.settlementCurrency())));

        return ResponseEntity
                .created(uri.path("/api/v1/merchants/{id}").build(body.merchantId()))
                .body(body);
    }

    @GetMapping("/{merchantId}")
    public MerchantResponse get(@PathVariable UUID merchantId) {
        return MerchantResponse.from(merchants.require(merchantId));
    }

    @PostMapping("/{merchantId}/terminals")
    public ResponseEntity<TerminalResponse> registerTerminal(@PathVariable UUID merchantId,
                                                             @Valid @RequestBody RegisterTerminalRequest request,
                                                             UriComponentsBuilder uri) {
        TerminalResponse body = TerminalResponse.from(
                merchants.registerTerminal(merchantId, request.terminalRef()));

        return ResponseEntity
                .created(uri.path("/api/v1/merchants/{m}/terminals/{t}").build(merchantId, body.terminalId()))
                .body(body);
    }

    @GetMapping("/{merchantId}/terminals")
    public List<TerminalResponse> terminals(@PathVariable UUID merchantId) {
        merchants.require(merchantId);
        return merchants.terminalsOf(merchantId).stream().map(TerminalResponse::from).toList();
    }
}
