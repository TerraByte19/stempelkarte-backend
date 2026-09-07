package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.wallet.CloudinaryService;
import com.example.stemplekarte.wallet.GoogleWalletService;
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
    private final GoogleWalletService googleWalletService;

    public StampDesignController(ShopRepository shopRepo, CardService cardService,
                                 CloudinaryService cloudinary,
                                 GoogleWalletService googleWalletService) {
        this.shopRepo = shopRepo;
        this.cardService = cardService;
        this.cloudinary = cloudinary;
        this.googleWalletService = googleWalletService;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    public record DesignRequest(
            String walletStyle, String stampIconType, String stampPreset,
            String stampColor, String emptyStampStyle,
            String colorBackground, String colorForeground, String colorLabel
    ) {}

    // originalBase64 (optional): unbeschnittenes Bild fuers spaetere
    // erneute Zuschneiden.
    public record ImageUploadRequest(String base64, String extension, String originalBase64) {}

    private boolean hasOriginal(ImageUploadRequest req) {
        return req.originalBase64() != null && !req.originalBase64().isBlank();
    }
    private String uploadOriginal(ImageUploadRequest req, String publicId) {
        return cloudinary.upload(req.originalBase64(), publicId, CloudinaryService.ImageType.ORIGINAL);
    }

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
        // Google Wallet Class neu schreiben → Farb-/Design-Änderung kommt bei gespeicherten Karten an
        googleWalletService.refreshClassForCard(card);
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
        Map<String, String> out = new HashMap<>();
        out.put("logoUrl", url);
        if (hasOriginal(req)) {
            String orig = uploadOriginal(req, "logo-orig-" + card.getId());
            card.setLogoOriginalUrl(orig);
            out.put("logoOriginalUrl", orig);
        }
        cardService.save(card);
        googleWalletService.refreshClassForCard(card);
        return out;
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
        Map<String, String> out = new HashMap<>();
        out.put("heroImageUrl", url);
        if (hasOriginal(req)) {
            String orig = uploadOriginal(req, "hero-orig-" + card.getId());
            card.setHeroOriginalUrl(orig);
            out.put("heroOriginalUrl", orig);
        }
        cardService.save(card);
        googleWalletService.refreshClassForCard(card);
        return out;
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
        Map<String, String> out = new HashMap<>();
        out.put("stampIconUrl", url);
        if (hasOriginal(req)) {
            String orig = uploadOriginal(req, "stamp-orig-" + card.getId());
            card.setStampIconOriginalUrl(orig);
            out.put("stampIconOriginalUrl", orig);
        }
        cardService.save(card);
        // Stempel-Icon betrifft nur Apple-Strip, kein Google-Class-Refresh nötig
        return out;
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
        map.put("stampIconOriginalUrl", shop.getStampIconOriginalUrl() != null ? shop.getStampIconOriginalUrl() : "");
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
        Map<String, String> out = new HashMap<>();
        out.put("stampIconUrl", url);
        if (hasOriginal(req)) {
            String orig = uploadOriginal(req, "stamp-orig-" + shop.getId());
            shop.setStampIconOriginalUrl(orig);
            out.put("stampIconOriginalUrl", orig);
        }
        shopRepo.save(shop);
        return out;
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
        map.put("logoOriginalUrl", card.getLogoOriginalUrl() != null ? card.getLogoOriginalUrl() : "");
        map.put("heroOriginalUrl", card.getHeroOriginalUrl() != null ? card.getHeroOriginalUrl() : "");
        map.put("stampIconOriginalUrl", card.getStampIconOriginalUrl() != null ? card.getStampIconOriginalUrl() : "");
        return map;
    }
}