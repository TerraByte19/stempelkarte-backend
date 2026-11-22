package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.LoginAttemptService;
import com.example.stemplekarte.service.FilialZugangService;
import com.example.stemplekarte.service.FilialenService;
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
    private final FilialZugangService filialZugangService;

    public AuthController(ShopService shopService, LoginAttemptService loginAttemptService,
                          FilialZugangService filialZugangService) {
        this.shopService = shopService;
        this.loginAttemptService = loginAttemptService;
        this.filialZugangService = filialZugangService;
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8) String password,
            @NotBlank String name
    ) {}

    // "email" bleibt der Feldname (Frontend), darf aber auch der Benutzername
    // eines Unterkontos sein (Tarif Kette). Mit @ = Laden, ohne @ = Unterkonto.
    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank String password
    ) {}

    // rolle SHOP oder FILIALE. ortId/ortLabel nur beim Unterkonto
    // (Hauptstandort = "HAUPT").
    public record AuthResponse(String token, String shopId, String name,
                               String rolle, String ortId, String ortLabel) {}

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
            AuthResponse antwort = req.email().contains("@")
                    ? ladenLogin(req)
                    : filialLogin(req);
            loginAttemptService.loginSucceeded(ip); // Zähler zurücksetzen
            return antwort;
        } catch (Exception e) {
            // Fehlversuch zählen, dann den ursprünglichen Fehler weiterreichen
            loginAttemptService.loginFailed(ip);
            throw e;
        }
    }

    private AuthResponse ladenLogin(LoginRequest req) {
        String token = shopService.login(req.email(), req.password());
        Shop shop = shopService.getByEmail(req.email());
        return new AuthResponse(token, shop.getId(), shop.getName(), "SHOP", null, null);
    }

    private AuthResponse filialLogin(LoginRequest req) {
        FilialZugangService.Anmeldung a = filialZugangService.login(req.email(), req.password());
        FilialZugang z = a.zugang();
        String ortId = z.getOrtId() != null ? z.getOrtId() : FilialenService.HAUPT;
        return new AuthResponse(a.token(), z.getShop().getId(), z.getShop().getName(),
                "FILIALE", ortId, a.ortLabel());
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