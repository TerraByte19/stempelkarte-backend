package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Shop", description = "Shop-Profil und Karten-Verwaltung")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/shop")
public class ShopController {

    private final ShopService shopService;
    private final CardService cardService;

    public ShopController(ShopService shopService, CardService cardService) {
        this.shopService = shopService;
        this.cardService = cardService;
    }

    public record UpdateProfileRequest(String name, String logoUrl,
                                       String colorBackground, String colorForeground,
                                       String colorLabel) {}

    public record CreateCardRequest(
            @NotBlank String name,
            @NotBlank String description,
            int rewardThreshold,
            @NotBlank String rewardText
    ) {}

    public record CardResponse(String id, String name, String description,
                               int rewardThreshold, String rewardText) {
        static CardResponse from(Card c) {
            return new CardResponse(c.getId(), c.getName(), c.getDescription(),
                    c.getRewardThreshold(), c.getRewardText());
        }
    }

    public record StaffTokenResponse(String token, String label) {
        static StaffTokenResponse from(StaffToken t) {
            return new StaffTokenResponse(t.getToken(), t.getLabel());
        }
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    @Operation(summary = "Eigenes Shop-Profil abrufen")
    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        Shop shop = currentShop(auth);
        return Map.of(
                "id", shop.getId(),
                "name", shop.getName(),
                "email", shop.getEmail(),
                "colorBackground", shop.getColorBackground(),
                "colorForeground", shop.getColorForeground(),
                "colorLabel", shop.getColorLabel(),
                "logoUrl", shop.getLogoUrl() != null ? shop.getLogoUrl() : ""
        );
    }

    @Operation(summary = "Shop-Profil aktualisieren (Name, Farben, Logo)")
    @PutMapping("/me")
    public Map<String, Object> updateProfile(@RequestBody UpdateProfileRequest req,
                                             Authentication auth) {
        Shop shop = currentShop(auth);
        Shop updated = shopService.updateProfile(shop.getId(), req.name(), req.logoUrl(),
                req.colorBackground(), req.colorForeground(), req.colorLabel());
        return Map.of(
                "id", updated.getId(),
                "name", updated.getName(),
                "colorBackground", updated.getColorBackground(),
                "colorForeground", updated.getColorForeground(),
                "colorLabel", updated.getColorLabel()
        );
    }

    @Operation(summary = "Neue Stempelkarte erstellen")
    @PostMapping("/cards")
    public CardResponse createCard(@RequestBody CreateCardRequest req, Authentication auth) {
        Shop shop = currentShop(auth);
        Card card = cardService.create(shop, req.name(), req.description(),
                req.rewardThreshold(), req.rewardText());
        return CardResponse.from(card);
    }

    @Operation(summary = "Alle eigenen Karten auflisten")
    @GetMapping("/cards")
    public List<CardResponse> listCards(Authentication auth) {
        Shop shop = currentShop(auth);
        return cardService.getByShop(shop).stream().map(CardResponse::from).toList();
    }

    @Operation(summary = "Staff-Token erstellen (für Mitarbeiter-Scanner)")
    @PostMapping("/staff-token")
    public StaffTokenResponse createStaffToken(@RequestBody Map<String, String> body,
                                               Authentication auth) {
        Shop shop = currentShop(auth);
        String label = body.getOrDefault("label", "Mitarbeiter");
        StaffToken token = shopService.createStaffToken(shop.getId(), label);
        return StaffTokenResponse.from(token);
    }

    @Operation(summary = "Alle Staff-Tokens auflisten")
    @GetMapping("/staff-tokens")
    public List<StaffTokenResponse> listStaffTokens(Authentication auth) {
        Shop shop = currentShop(auth);
        return shopService.getStaffTokens(shop.getId()).stream()
                .map(StaffTokenResponse::from).toList();
    }
}