package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.JwtService;
import com.example.stemplekarte.service.ShopService;
import com.example.stemplekarte.wallet.GoogleWalletSetup;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

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
    private final ShopService shopService;
    private final PasswordEncoder passwordEncoder;

    @Value("${stempelkarte.admin-secret:admin-geheim-nur-lokal}")
    private String adminSecret;

    // Pro IP: [0] = Anzahl Fehlversuche, [1] = Start des Zeitfensters in ms.
    // WICHTIG: long[] statt int[] — der Timestamp passt nicht in einen int
    // (Ueberlauf), genau das hat den alten Limiter wirkungslos gemacht.
    private final ConcurrentHashMap<String, long[]> rateLimitMap = new ConcurrentHashMap<>();
    private static final int MAX_ATTEMPTS = 5;
    private static final long BLOCK_DURATION_MS = 15 * 60 * 1000L;

    public AdminController(GoogleWalletSetup googleWalletSetup,
                           ShopRepository shopRepo,
                           CardRepository cardRepo,
                           CustomerCardRepository customerCardRepo,
                           JwtService jwtService,
                           ShopService shopService,
                           PasswordEncoder passwordEncoder) {
        this.googleWalletSetup = googleWalletSetup;
        this.shopRepo = shopRepo;
        this.cardRepo = cardRepo;
        this.customerCardRepo = customerCardRepo;
        this.jwtService = jwtService;
        this.shopService = shopService;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @RequestHeader(value = "X-Real-IP", required = false) String realIp) {

        String ip = resolveIp(forwardedFor, realIp);
        String password = body.get("password");
        long now = System.currentTimeMillis();

        long[] data = rateLimitMap.get(ip);

        // Abgelaufenes Zeitfenster: Zaehler zuruecksetzen
        if (data != null && (now - data[1]) >= BLOCK_DURATION_MS) {
            rateLimitMap.remove(ip);
            data = null;
        }

        // Innerhalb des Fensters und Limit erreicht -> blocken
        if (data != null && data[0] >= MAX_ATTEMPTS) {
            long remainingMin = (BLOCK_DURATION_MS - (now - data[1])) / 60000 + 1;
            return ResponseEntity.status(429).body(
                    Map.of("error", "Zu viele Versuche. Warte " + remainingMin + " Minuten.")
            );
        }

        if (password == null || !adminSecret.equals(password)) {
            if (data == null) {
                rateLimitMap.put(ip, new long[]{1L, now});
            } else {
                data[0]++; // Fensterstart bleibt erhalten
            }
            long attempts = (data == null) ? 1 : data[0];
            long remaining = Math.max(0, MAX_ATTEMPTS - attempts);
            return ResponseEntity.status(401).body(
                    Map.of("error", "Falsches Passwort. Noch " + remaining + " Versuche.")
            );
        }

        // Erfolg: Zaehler fuer diese IP loeschen
        rateLimitMap.remove(ip);
        String token = jwtService.generateAdminToken();
        return ResponseEntity.ok(Map.of("token", token));
    }

    // Hinweis: X-Forwarded-For ist vom Client manipulierbar. Auf Render setzt
    // der Proxy den echten Client-IP. Fuer einen wirklich robusten Schutz waere
    // eine echte Rate-Limit-Schicht (z.B. bucket4j oder Cloudflare) der naechste
    // Schritt — dieser Limiter ist die einfache In-Memory-Variante.
    private String resolveIp(String forwardedFor, String realIp) {
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // Erstes Element ist der urspruengliche Client
            return forwardedFor.split(",")[0].trim();
        }
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return "unknown";
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
    public ResponseEntity<List<Map<String, Object>>> getAllShops() {
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
            map.put("maxTokens", shop.getMaxTokens());
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    @PostMapping("/shops/{shopId}/toggle")
    public ResponseEntity<Map<String, Object>> toggleShop(@PathVariable String shopId) {
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

    public record CreateShopRequest(String email, String password, String name, int maxTokens) {}

    @PostMapping("/shops/create")
    public ResponseEntity<Map<String, Object>> createShop(@RequestBody CreateShopRequest req) {
        try {
            int maxTokens = req.maxTokens() > 0 ? req.maxTokens() : 3;
            Shop shop = shopService.register(req.email(), req.password(), req.name(), maxTokens);
            Map<String, Object> result = new HashMap<>();
            result.put("id", shop.getId());
            result.put("name", shop.getName());
            result.put("email", shop.getEmail());
            result.put("maxTokens", shop.getMaxTokens());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }

    public record ChangePasswordRequest(String shopId, String newPassword) {}

    @PostMapping("/shops/password")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody ChangePasswordRequest req) {
        try {
            Shop shop = shopRepo.findById(req.shopId())
                    .orElseThrow(() -> new RuntimeException("Shop nicht gefunden"));
            shop.setPasswordHash(passwordEncoder.encode(req.newPassword()));
            shopRepo.save(shop);
            return ResponseEntity.ok(Map.of("message", "Passwort geandert"));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }
}