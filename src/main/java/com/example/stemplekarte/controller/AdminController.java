package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.JwtService;
import com.example.stemplekarte.wallet.GoogleWalletSetup;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Tag(name = "Admin", description = "Admin-Endpoints")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final GoogleWalletSetup googleWalletSetup;
    private final ShopRepository shopRepo;
    private final CardRepository cardRepo;
    private final CustomerCardRepository customerCardRepo;
    private final JwtService jwtService;

    @Value("${stempelkarte.admin-secret:${ADMIN_SECRET:admin-secret-bitte-aendern}}")
    private String adminSecret;

    // Rate-Limiting: IP -> [attempts, firstAttemptTime]
    private final ConcurrentHashMap<String, int[]> rateLimitMap = new ConcurrentHashMap<>();
    private static final int MAX_ATTEMPTS = 5;
    private static final long BLOCK_DURATION_MS = 15 * 60 * 1000L; // 15 Minuten

    public AdminController(GoogleWalletSetup googleWalletSetup,
                           ShopRepository shopRepo,
                           CardRepository cardRepo,
                           CustomerCardRepository customerCardRepo,
                           JwtService jwtService) {
        this.googleWalletSetup = googleWalletSetup;
        this.shopRepo = shopRepo;
        this.cardRepo = cardRepo;
        this.customerCardRepo = customerCardRepo;
        this.jwtService = jwtService;
    }

    private void checkAdminToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new SecurityException("Nicht autorisiert");
        }
        String token = authHeader.substring(7);
        if (!jwtService.isAdmin(token)) {
            throw new SecurityException("Nicht autorisiert");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @RequestHeader(value = "X-Real-IP", required = false) String realIp) {

        String ip = forwardedFor != null ? forwardedFor : (realIp != null ? realIp : "unknown");
        String password = body.get("password");

        // Rate-Limiting prüfen
        int[] rateData = rateLimitMap.getOrDefault(ip, new int[]{0, 0});
        int attempts = rateData[0];
        long firstAttempt = rateData[1];

        // Block zurücksetzen nach 15 Minuten
        if (attempts >= MAX_ATTEMPTS) {
            long elapsed = System.currentTimeMillis() - firstAttempt;
            if (elapsed < BLOCK_DURATION_MS) {
                long remainingMin = (BLOCK_DURATION_MS - elapsed) / 60000;
                return ResponseEntity.status(429).body(
                        Map.of("error", "Zu viele Versuche. Warte " + remainingMin + " Minuten.")
                );
            } else {
                rateLimitMap.remove(ip);
                attempts = 0;
            }
        }

        if (!adminSecret.equals(password)) {
            rateLimitMap.put(ip, new int[]{attempts + 1,
                    attempts == 0 ? (int) System.currentTimeMillis() : (int) firstAttempt});
            int remaining = MAX_ATTEMPTS - (attempts + 1);
            return ResponseEntity.status(401).body(
                    Map.of("error", "Falsches Passwort. Noch " + remaining + " Versuche.")
            );
        }

        rateLimitMap.remove(ip);
        String token = jwtService.generateAdminToken();
        return ResponseEntity.ok(Map.of("token", token));
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

    @GetMapping("/shops")
    public ResponseEntity<List<Map<String, Object>>> getAllShops(
            @RequestHeader("Authorization") String authHeader) {
        checkAdminToken(authHeader);

        List<Map<String, Object>> result = shopRepo.findAll().stream().map(shop -> {
            int cardCount = cardRepo.findByShopAndActiveTrue(shop).size();
            int customerCount = customerCardRepo.countByCard_Shop(shop);
            Map<String, Object> map = new HashMap<>();
            map.put("id", shop.getId());
            map.put("name", shop.getName());
            map.put("email", shop.getEmail());
            map.put("active", shop.isActive());
            map.put("cardCount", cardCount);
            map.put("customerCount", customerCount);
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    @PostMapping("/shops/{shopId}/toggle")
    public ResponseEntity<Map<String, Object>> toggleShop(
            @PathVariable String shopId,
            @RequestHeader("Authorization") String authHeader) {
        checkAdminToken(authHeader);

        Shop shop = shopRepo.findById(shopId)
                .orElseThrow(() -> new RuntimeException("Shop nicht gefunden"));
        shop.setActive(!shop.isActive());
        shopRepo.save(shop);

        Map<String, Object> result = new HashMap<>();
        result.put("id", shop.getId());
        result.put("name", shop.getName());
        result.put("active", shop.isActive());
        return ResponseEntity.ok(result);
    }
}