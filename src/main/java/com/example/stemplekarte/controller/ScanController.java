package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.StaffTokenFilter;
import com.example.stemplekarte.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "Scan", description = "Stempel vergeben — braucht X-Staff-Token Header")
@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private final CustomerService service;

    public ScanController(CustomerService service) {
        this.service = service;
    }

    public record ScanRequest(@NotBlank String qrPayload) {}
    public record ScanResponse(String action, String message, String customerId,
                               String cardId, int stamps, int totalRewards,
                               int rewardThreshold) {}

    @Operation(summary = "QR-Code scannen und Stempel vergeben",
            description = "Erfordert X-Staff-Token Header")
    @PostMapping
    public ScanResponse scan(@RequestBody ScanRequest req, Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }

        Shop shop = ((StaffTokenFilter.StaffPrincipal) auth.getPrincipal()).staff().getShop();
        ScanResult result = service.processScan(req.qrPayload(), shop);

        String action = switch (result) {
            case ScanResult.Stamped s -> "stamped";
            case ScanResult.Full f -> "full";
            case ScanResult.Redeemed r -> "redeemed";
        };

        var cc = result.customerCard();
        return new ScanResponse(
                action, result.message(),
                cc.getCustomer().getId(),
                cc.getCard().getId(),
                cc.getStamps(),
                cc.getTotalRewards(),
                cc.getCard().getRewardThreshold()
        );
    }
}