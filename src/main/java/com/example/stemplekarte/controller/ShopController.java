package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.ShopService;
import com.example.stemplekarte.wallet.CloudinaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Shop", description = "Shop-Profil und Karten-Verwaltung")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/shop")
public class ShopController {

    private final ShopService shopService;
    private final CardService cardService;
    private final CustomerCardRepository customerCardRepo;
    private final CloudinaryService cloudinaryService;

    public ShopController(ShopService shopService, CardService cardService,
                          CustomerCardRepository customerCardRepo, CloudinaryService cloudinaryService) {
        this.shopService = shopService;
        this.cardService = cardService;
        this.customerCardRepo = customerCardRepo;
        this.cloudinaryService = cloudinaryService;
    }

    public record UpdateProfileRequest(String name, String logoUrl,
                                       String colorBackground, String colorForeground,
                                       String colorLabel) {}

    public record CreateCardRequest(
            @NotBlank String name,
            @NotBlank String description,
            int rewardThreshold,
            @NotBlank String rewardText,
            String walletStyle,
            String stampIconType,
            String stampPreset,
            String stampColor,
            String emptyStampStyle,
            String colorBackground,
            String colorForeground,
            String colorLabel,
            String logoUrl,
            String heroImageUrl,
            String stampIconUrl
    ) {}

    public record CardResponse(
            String id, String name, String description,
            int rewardThreshold, String rewardText,
            String walletStyle, String stampIconType, String stampPreset,
            String stampColor, String emptyStampStyle, String stampIconUrl,
            String colorBackground, String colorForeground, String colorLabel,
            String logoUrl, String heroImageUrl
    ) {
        static CardResponse from(Card c) {
            return new CardResponse(
                    c.getId(), c.getName(), c.getDescription(),
                    c.getRewardThreshold(), c.getRewardText(),
                    c.getWalletStyle(), c.getStampIconType(), c.getStampPreset(),
                    c.getStampColor(), c.getEmptyStampStyle(),
                    c.getStampIconUrl() != null ? c.getStampIconUrl() : "",
                    c.getColorBackground(), c.getColorForeground(), c.getColorLabel(),
                    c.getLogoUrl() != null ? c.getLogoUrl() : "",
                    c.getHeroImageUrl() != null ? c.getHeroImageUrl() : ""
            );
        }
    }

    public record StaffTokenResponse(String token, String label) {
        static StaffTokenResponse from(StaffToken t) {
            return new StaffTokenResponse(t.getToken(), t.getLabel());
        }
    }

    // Request-Objekt für die Base64 Bild-Uploads vom Frontend
    public record ImageUploadRequest(String base64, String extension) {}

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    // --- BILD UPLOAD ENDPOINTS ---






    // --- STANDARD SHOP ENDPOINTS ---

    @Operation(summary = "Eigenes Shop-Profil abrufen")
    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        Shop shop = currentShop(auth);
        Map<String, Object> map = new HashMap<>();
        map.put("id", shop.getId());
        map.put("name", shop.getName());
        map.put("email", shop.getEmail());
        map.put("colorBackground", shop.getColorBackground());
        map.put("colorForeground", shop.getColorForeground());
        map.put("colorLabel", shop.getColorLabel());
        map.put("logoUrl", shop.getLogoUrl() != null ? shop.getLogoUrl() : "");
        map.put("heroImageUrl", shop.getHeroImageUrl() != null ? shop.getHeroImageUrl() : "");
        return map;
    }

    @Operation(summary = "Shop-Profil aktualisieren")
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
        card.updateDesign(req.walletStyle(), req.stampIconType(), req.stampPreset(),
                req.stampColor(), req.emptyStampStyle());
        card.updateColors(req.colorBackground(), req.colorForeground(), req.colorLabel());

        // URLs setzen, falls sie beim Erstellen mitgeschickt wurden
        if (req.logoUrl() != null) card.setLogoUrl(req.logoUrl());
        if (req.heroImageUrl() != null) card.setHeroImageUrl(req.heroImageUrl());
        if (req.stampIconUrl() != null) card.setStampIconUrl(req.stampIconUrl());

        cardService.save(card);
        return CardResponse.from(card);
    }

    @Operation(summary = "Alle eigenen Karten auflisten")
    @GetMapping("/cards")
    public List<CardResponse> listCards(Authentication auth) {
        Shop shop = currentShop(auth);
        return cardService.getByShop(shop).stream().map(CardResponse::from).toList();
    }

    @Operation(summary = "Karte deaktivieren")
    @DeleteMapping("/cards/{cardId}")
    public ResponseEntity<Map<String, String>> deleteCard(@PathVariable String cardId,
                                                          Authentication auth) {
        Shop shop = currentShop(auth);
        cardService.deactivate(cardId, shop);
        return ResponseEntity.ok(Map.of("message", "Karte deaktiviert"));
    }

    @Operation(summary = "Statistiken pro Karte")
    @GetMapping("/stats")
    public List<Map<String, Object>> stats(Authentication auth) {
        Shop shop = currentShop(auth);
        return cardService.getByShop(shop).stream().map(card -> {
            List<CustomerCard> customerCards = customerCardRepo.findByCard(card);
            int totalStamps = customerCards.stream().mapToInt(CustomerCard::getStamps).sum();
            int totalRewards = customerCards.stream().mapToInt(CustomerCard::getTotalRewards).sum();
            Map<String, Object> map = new HashMap<>();
            map.put("cardId", card.getId());
            map.put("cardName", card.getName());
            map.put("customerCount", customerCards.size());
            map.put("totalStamps", totalStamps);
            map.put("totalRewards", totalRewards);
            return map;
        }).toList();
    }

    @Operation(summary = "Staff-Token erstellen")
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