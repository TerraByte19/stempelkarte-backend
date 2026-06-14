package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.LoginAttemptService;
import com.example.stemplekarte.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

// KEIN @CrossOrigin hier — CORS läuft global über SecurityConfig
@Tag(name = "Auth", description = "Shop-Registrierung und Login")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ShopService shopService;
    private final LoginAttemptService loginAttemptService;

    public AuthController(ShopService shopService, LoginAttemptService loginAttemptService) {
        this.shopService = shopService;
        this.loginAttemptService = loginAttemptService;
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8) String password,
            @NotBlank String name
    ) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record AuthResponse(String token, String shopId, String name) {}

    @Operation(summary = "Shop registrieren — deaktiviert, nur über Admin-Panel")
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(403).body(
                Map.of("error", "Registrierung nur über Admin moeglich")
        );
    }

    @Operation(summary = "Shop einloggen")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String ip = clientIp(request);

        // Brute-Force-Schutz: gesperrte IPs sofort abweisen
        if (loginAttemptService.isBlocked(ip)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Zu viele fehlgeschlagene Login-Versuche. Bitte in einigen Minuten erneut versuchen.");
        }

        try {
            String token = shopService.login(req.email(), req.password());
            Shop shop = shopService.getByEmail(req.email());
            loginAttemptService.loginSucceeded(ip); // Zähler zurücksetzen
            return new AuthResponse(token, shop.getId(), shop.getName());
        } catch (Exception e) {
            // Fehlversuch zählen, dann den ursprünglichen Fehler weiterreichen
            loginAttemptService.loginFailed(ip);
            throw e;
        }
    }

    /**
     * Ermittelt die echte Client-IP. Hinter Render/Proxies steht die echte
     * IP im X-Forwarded-For Header (erste Adresse der Liste).
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}