package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.StaffTokenFilter;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.wallet.ApnsPushService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "Scan", description = "Stempel vergeben — braucht X-Staff-Token Header")
@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private static final Logger log = LoggerFactory.getLogger(ScanController.class);

    private final CustomerService service;
    private final ApnsPushService apnsPushService;
    private final GoogleWalletService googleWalletService;

    public ScanController(CustomerService service,
                          ApnsPushService apnsPushService,
                          GoogleWalletService googleWalletService) {
        this.service = service;
        this.apnsPushService = apnsPushService;
        this.googleWalletService = googleWalletService;
    }

    public record ScanRequest(
            @NotBlank String qrPayload,
            @Min(1) @Max(20) int count
    ) {}

    public record ScanResponse(
            String action, String message, String customerId,
            String cardId, int stamps, int totalRewards,
            int rewardThreshold, int stampsAdded
    ) {}

    @Operation(summary = "QR-Code scannen und Stempel vergeben",
            description = "Erfordert X-Staff-Token Header. count = Anzahl Stempel (1-20)")
    @PostMapping
    public ScanResponse scan(@RequestBody ScanRequest req, Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }

        Shop shop = ((StaffTokenFilter.StaffPrincipal) auth.getPrincipal()).staff().getShop();
        int count = req.count() <= 0 ? 1 : req.count();

        ScanResult result = service.processScan(req.qrPayload(), shop, count);
        var cc = result.customerCard();

        // ── Apple Wallet: stiller Push → iPhone holt neue Karte ──────────
        try {
            apnsPushService.notifyUpdate(cc.getId());
        } catch (Exception e) {
            log.warn("APNs Push fehlgeschlagen (nicht kritisch): {}", e.getMessage());
        }

        // ── Google Wallet: Loyalty Object direkt per API updaten ──────────
        try {
            googleWalletService.notifyUpdate(cc);
        } catch (Exception e) {
            log.warn("Google Wallet Update fehlgeschlagen (nicht kritisch): {}", e.getMessage());
        }

        String action = switch (result) {
            case ScanResult.Stamped s -> "stamped";
            case ScanResult.Full f -> "full";
            case ScanResult.Redeemed r -> "redeemed";
        };

        return new ScanResponse(
                action, result.message(),
                cc.getCustomer().getId(),
                cc.getCard().getId(),
                cc.getStamps(),
                cc.getTotalRewards(),
                cc.getCard().getRewardThreshold(),
                count
        );
    }
}