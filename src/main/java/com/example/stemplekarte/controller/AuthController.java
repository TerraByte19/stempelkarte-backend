package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// KEIN @CrossOrigin hier — CORS läuft global über SecurityConfig
@Tag(name = "Auth", description = "Shop-Registrierung und Login")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ShopService shopService;

    public AuthController(ShopService shopService) {
        this.shopService = shopService;
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
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        String token = shopService.login(req.email(), req.password());
        Shop shop = shopService.getByEmail(req.email());
        return new AuthResponse(token, shop.getId(), shop.getName());
    }
}