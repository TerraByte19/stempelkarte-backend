package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

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

    @Operation(summary = "Shop registrieren")
    @PostMapping("/register")
    public AuthResponse register(@RequestBody RegisterRequest req) {
        Shop shop = shopService.register(req.email(), req.password(), req.name(), 3);
        String token = shopService.login(req.email(), req.password());
        return new AuthResponse(token, shop.getId(), shop.getName());
    }

    @Operation(summary = "Shop einloggen")
    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest req) {
        String token = shopService.login(req.email(), req.password());
        Shop shop = shopService.getByEmail(req.email());
        return new AuthResponse(token, shop.getId(), shop.getName());
    }
}