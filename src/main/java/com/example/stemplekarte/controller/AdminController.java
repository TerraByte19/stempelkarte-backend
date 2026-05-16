package com.example.stemplekarte.controller;

import com.example.stemplekarte.wallet.GoogleWalletSetup;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin", description = "Einmalige Setup-Endpoints")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final GoogleWalletSetup googleWalletSetup;

    public AdminController(GoogleWalletSetup googleWalletSetup) {
        this.googleWalletSetup = googleWalletSetup;
    }

    @PostMapping("/setup-google-wallet")
    public ResponseEntity<String> setupGoogleWallet() {
        try {
            String result = googleWalletSetup.createLoyaltyClass();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Fehler: " + e.getMessage());
        }
    }
}