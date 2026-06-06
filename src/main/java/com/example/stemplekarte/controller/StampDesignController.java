package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.wallet.CloudinaryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Design", description = "Karten-Design pro Karte")
@RestController
@RequestMapping("/api/shop")
public class StampDesignController {

    private final ShopRepository shopRepo;
    private final CardService cardService;
    private final CloudinaryService cloudinary;

    public StampDesignController(ShopRepository shopRepo, CardService cardService,
                                 CloudinaryService cloudinary) {
        this.shopRepo = shopRepo;
        this.cardService = cardService;
        this.cloudinary = cloudinary;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    public record DesignRequest(
            String walletStyle, String stampIconType, String stampPreset,
            String stampColor, String emptyStampStyle,
            String colorBackground, String colorForeground, String colorLabel
    ) {}

    public record ImageUploadRequest(String base64, String extension) {}

    // ── Design einer Karte abrufen ────────────────────────────────────────
    @GetMapping("/cards/{cardId}/design")
    public Map<String, Object> getCardDesign(@PathVariable String cardId, Authentication auth) {
        Shop shop = currentShop(auth);
        Card card = cardService.getByIdAndShop(cardId, shop);
        return toMap(card);
    }

    // ── Komplettes Design einer Karte speichern ───────────────────────────
    @PutMapping("/cards/{cardId}/design")
    public Map<String, Object> updateCardDesign(@PathVariable String cardId,
                                                @RequestBody DesignRequest req,
                                                Authentication auth) {
        Shop shop = currentShop(auth);
        Card card = cardService.getByIdAndShop(cardId, shop);
        card.updateDesign(req.walletStyle(), req.stampIconType(), req.stampPreset(),
                req.stampColor(), req.emptyStampStyle());
        card.updateColors(req.colorBackground(), req.colorForeground(), req.colorLabel());
        cardService.save(card);
        return toMap(card);
    }

    // ── Logo für eine Karte hochladen ─────────────────────────────────────
    @PostMapping("/cards/{cardId}/logo")
    public Map<String, String> uploadCardLogo(@PathVariable String cardId,
                                              @RequestBody ImageUploadRequest req,
                                              Authentication auth) {
        Shop shop = currentShop(auth);
        Card card = cardService.getByIdAndShop(cardId, shop);
        String url = cloudinary.upload(req.base64(), "logo-" + card.getId(),
                CloudinaryService.ImageType.LOGO);
        card.setLogoUrl(url);
        cardService.save(card);
        return Map.of("logoUrl", url);
    }

    // ── Hero/Banner für eine Karte hochladen ──────────────────────────────
    @PostMapping("/cards/{cardId}/hero")
    public Map<String, String> uploadCardHero(@PathVariable String cardId,
                                              @RequestBody ImageUploadRequest req,
                                              Authentication auth) {
        Shop shop = currentShop(auth);
        Card card = cardService.getByIdAndShop(cardId, shop);
        String url = cloudinary.upload(req.base64(), "hero-" + card.getId(),
                CloudinaryService.ImageType.HERO);
        card.setHeroImageUrl(url);
        cardService.save(card);
        return Map.of("heroImageUrl", url);
    }

    // ── Stempel-Icon für eine Karte hochladen ─────────────────────────────
    @PostMapping("/cards/{cardId}/stamp-icon")
    public Map<String, String> uploadCardStampIcon(@PathVariable String cardId,
                                                   @RequestBody ImageUploadRequest req,
                                                   Authentication auth) {
        Shop shop = currentShop(auth);
        Card card = cardService.getByIdAndShop(cardId, shop);
        String url = cloudinary.upload(req.base64(), "stamp-" + card.getId(),
                CloudinaryService.ImageType.STAMP);
        card.setStampIconUrl(url);
        cardService.save(card);
        return Map.of("stampIconUrl", url);
    }

    // ── Alte Shop-weite Endpoints (Rückwärtskompatibilität) ────────────────
    @GetMapping("/design")
    public Map<String, Object> getShopDesign(Authentication auth) {
        Shop shop = currentShop(auth);
        Map<String, Object> map = new HashMap<>();
        map.put("walletStyle", shop.getWalletStyle() != null ? shop.getWalletStyle() : "number");
        map.put("stampIconType", shop.getStampIconType() != null ? shop.getStampIconType() : "preset");
        map.put("stampPreset", shop.getStampPreset() != null ? shop.getStampPreset() : "coffee");
        map.put("stampColor", shop.getStampColor() != null ? shop.getStampColor() : "#6F4E37");
        map.put("emptyStampStyle", shop.getEmptyStampStyle() != null ? shop.getEmptyStampStyle() : "number");
        map.put("stampIconUrl", shop.getStampIconUrl() != null ? shop.getStampIconUrl() : "");
        return map;
    }

    @PutMapping("/design")
    public Map<String, Object> updateShopDesign(@RequestBody DesignRequest req, Authentication auth) {
        Shop shop = currentShop(auth);
        shop.updateDesign(req.walletStyle(), req.stampIconType(), req.stampPreset(),
                req.stampColor(), req.emptyStampStyle());
        shopRepo.save(shop);
        return getShopDesign(auth);
    }

    @PostMapping("/stamp-icon")
    public Map<String, String> uploadShopStampIcon(@RequestBody ImageUploadRequest req,
                                                   Authentication auth) {
        Shop shop = currentShop(auth);
        String url = cloudinary.upload(req.base64(), "stamp-" + shop.getId(),
                CloudinaryService.ImageType.STAMP);
        shop.setStampIconUrl(url);
        shopRepo.save(shop);
        return Map.of("stampIconUrl", url);
    }

    private Map<String, Object> toMap(Card card) {
        Map<String, Object> map = new HashMap<>();
        map.put("walletStyle", card.getWalletStyle());
        map.put("stampIconType", card.getStampIconType());
        map.put("stampPreset", card.getStampPreset());
        map.put("stampColor", card.getStampColor());
        map.put("emptyStampStyle", card.getEmptyStampStyle());
        map.put("stampIconUrl", card.getStampIconUrl() != null ? card.getStampIconUrl() : "");
        map.put("colorBackground", card.getColorBackground());
        map.put("colorForeground", card.getColorForeground());
        map.put("colorLabel", card.getColorLabel());
        map.put("logoUrl", card.getLogoUrl() != null ? card.getLogoUrl() : "");
        map.put("heroImageUrl", card.getHeroImageUrl() != null ? card.getHeroImageUrl() : "");
        return map;
    }
}