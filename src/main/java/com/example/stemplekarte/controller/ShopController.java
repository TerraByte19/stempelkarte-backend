package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.*;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.SentNewsletterRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.EmailService;
import com.example.stemplekarte.service.ShopService;
import com.example.stemplekarte.wallet.CloudinaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
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
    private final EmailService emailService;
    private final SentNewsletterRepository sentNewsletterRepo;
    private final ScanLogRepository scanLogRepo;

    @Value("${stempelkarte.base-url:http://localhost:8080}")
    private String baseUrl;

    public ShopController(ShopService shopService, CardService cardService,
                          CustomerCardRepository customerCardRepo, CloudinaryService cloudinaryService,
                          EmailService emailService, SentNewsletterRepository sentNewsletterRepo,
                          ScanLogRepository scanLogRepo) {
        this.shopService = shopService;
        this.cardService = cardService;
        this.customerCardRepo = customerCardRepo;
        this.cloudinaryService = cloudinaryService;
        this.emailService = emailService;
        this.sentNewsletterRepo = sentNewsletterRepo;
        this.scanLogRepo = scanLogRepo;
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

    @Operation(summary = "Gesamt-Statistik des Shops (aggregiert über alle Karten)")
    @GetMapping("/stats/summary")
    public Map<String, Object> statsSummary(Authentication auth) {
        Shop shop = currentShop(auth);
        List<Card> cards = cardService.getByShop(shop);

        int totalCustomers = 0;
        int totalStamps = 0;
        int totalRewards = 0;

        int customersWithReward = 0;   // Kunden mit mind. 1 Belohnung
        int customersNearReward = 0;   // Kunden ≥ 80% der Stempel (kurz vor Ziel)
        int customersWithConsent = 0;  // Kunden mit Marketing-Einwilligung
        int activeCustomers30d = 0;    // Kunden in den letzten 30 Tagen gestempelt
        double fillSum = 0;            // Summe der Füllgrade (für Durchschnitt)

        Instant thirtyDaysAgo = Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS);

        List<Map<String, Object>> perCard = new java.util.ArrayList<>();

        for (Card card : cards) {
            List<CustomerCard> ccs = customerCardRepo.findByCard(card);
            int threshold = Math.max(1, card.getRewardThreshold());
            int customers = ccs.size();
            int stamps = ccs.stream().mapToInt(CustomerCard::getStamps).sum();
            int rewards = ccs.stream().mapToInt(CustomerCard::getTotalRewards).sum();

            totalCustomers += customers;
            totalStamps += stamps;
            totalRewards += rewards;

            for (CustomerCard cc : ccs) {
                if (cc.getTotalRewards() > 0) customersWithReward++;
                if (cc.getStamps() >= threshold * 0.8) customersNearReward++;
                if (cc.isMarketingConsent()) customersWithConsent++;
                if (cc.getUpdatedAt() != null && cc.getUpdatedAt().isAfter(thirtyDaysAgo))
                    activeCustomers30d++;
                fillSum += (double) cc.getStamps() / threshold;
            }

            Map<String, Object> cardMap = new HashMap<>();
            cardMap.put("cardId", card.getId());
            cardMap.put("cardName", card.getName());
            cardMap.put("customerCount", customers);
            cardMap.put("totalStamps", stamps);
            cardMap.put("totalRewards", rewards);
            cardMap.put("rewardThreshold", card.getRewardThreshold());
            perCard.add(cardMap);
        }

        // Abgeleitete Kennzahlen (gerundet, gegen Division durch 0 abgesichert)
        double avgStampsPerCustomer = totalCustomers > 0
                ? Math.round((double) totalStamps / totalCustomers * 10) / 10.0 : 0;
        // Einlöse-Quote in Prozent: Anteil Kunden mit mind. 1 Belohnung
        int redemptionRate = totalCustomers > 0
                ? (int) Math.round((double) customersWithReward / totalCustomers * 100) : 0;
        // Durchschnittlicher Füllgrad in Prozent
        int avgFillPercent = totalCustomers > 0
                ? (int) Math.round(fillSum / totalCustomers * 100) : 0;

        // 30-Tage-Verlauf: Stempel & Belohnungen pro Tag aus dem ScanLog
        List<ScanLog> recent = scanLogRepo
                .findByShopIdAndScannedAtAfterOrderByScannedAtAsc(shop.getId(), thirtyDaysAgo);
        // Pro Tag (YYYY-MM-DD) aufsummieren
        java.util.Map<String, int[]> byDay = new java.util.TreeMap<>();
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        for (ScanLog sl : recent) {
            String day = sl.getScannedAt().atZone(zone).toLocalDate().toString();
            int[] v = byDay.computeIfAbsent(day, k -> new int[2]);
            v[0] += sl.getStampsAdded();
            v[1] += sl.getRewardsEarned();
        }
        List<Map<String, Object>> history = new java.util.ArrayList<>();
        for (var e : byDay.entrySet()) {
            Map<String, Object> dayMap = new HashMap<>();
            dayMap.put("date", e.getKey());
            dayMap.put("stamps", e.getValue()[0]);
            dayMap.put("rewards", e.getValue()[1]);
            history.add(dayMap);
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("shopName", shop.getName());
        summary.put("totalCards", cards.size());
        summary.put("totalCustomers", totalCustomers);
        summary.put("totalStamps", totalStamps);
        summary.put("totalRewards", totalRewards);
        summary.put("avgStampsPerCustomer", avgStampsPerCustomer);
        summary.put("redemptionRate", redemptionRate);
        summary.put("customersNearReward", customersNearReward);
        summary.put("avgFillPercent", avgFillPercent);
        summary.put("customersWithConsent", customersWithConsent);
        summary.put("activeCustomers30d", activeCustomers30d);
        summary.put("perCard", perCard);
        summary.put("history", history);
        return summary;
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

    // --- NEWSLETTER ---

    // imageUrls ist optional: Liste von URLs der via /api/shop/newsletter/image
    // hochgeladenen Bilder, die im Newsletter angezeigt werden.
    // Längen-Limits schützen Server/DB vor übergroßen Eingaben.
    public record NewsletterRequest(
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 10000) String body,
            @Size(max = 10) java.util.List<String> imageUrls) {}

    @Operation(summary = "Anzahl Kunden mit Werbe-Einwilligung (Vorschau für Newsletter)")
    @GetMapping("/newsletter/recipients")
    public Map<String, Object> newsletterRecipients(Authentication auth) {
        Shop shop = currentShop(auth);
        List<CustomerCard> all =
                customerCardRepo.findByCard_ShopAndMarketingConsentTrue(shop);
        // Bestätigte (Double-Opt-In) zählen separat — nur die bekommen wirklich Mails
        long confirmed = all.stream()
                .filter(cc -> cc.getCustomer().isEmailConfirmed())
                .count();
        return Map.of(
                "total", all.size(),
                "confirmed", confirmed
        );
    }

    @Operation(summary = "Test-Newsletter nur an die eigene Shop-E-Mail senden (Vorschau)")
    @PostMapping("/newsletter/test")
    public Map<String, Object> sendTestNewsletter(@Valid @org.springframework.web.bind.annotation.RequestBody
                                                  NewsletterRequest req,
                                                  Authentication auth) {
        Shop shop = currentShop(auth);

        // Test-Versand geht NUR an die eigene Shop-E-Mail, wird NICHT im
        // Verlauf gespeichert und zählt nicht als echter Kunden-Versand.
        // Die Abmelde-/Lösch-Links zeigen auf Beispielwerte, damit die Mail
        // exakt wie eine echte aussieht (Buttons sind im Test nicht aktiv).
        String unsubscribeUrl = baseUrl + "/mail/unsubscribe?cc=TEST&t=TEST";
        String deleteUrl = baseUrl + "/mail/delete-request?c=TEST";

        emailService.sendNewsletterMail(
                shop.getEmail(),               // nur an den Laden selbst
                shop,                          // Branding (Logo + Hero-Bild)
                shop.getEmail(),               // Reply-To
                "[TEST] " + req.subject(),     // Betreff als Test markiert
                req.body(),
                req.imageUrls(),
                unsubscribeUrl,
                deleteUrl
        );

        return Map.of("sentTo", shop.getEmail());
    }

    @Operation(summary = "Newsletter an alle Kunden mit Einwilligung versenden")
    @PostMapping("/newsletter")
    public Map<String, Object> sendNewsletter(@Valid @org.springframework.web.bind.annotation.RequestBody
                                              NewsletterRequest req,
                                              Authentication auth) {
        Shop shop = currentShop(auth);
        List<CustomerCard> recipients =
                customerCardRepo.findByCard_ShopAndMarketingConsentTrue(shop);

        int sent = 0;
        int skipped = 0;
        for (CustomerCard cc : recipients) {
            // Double-Opt-In: nur an bestätigte E-Mails senden (gesetzlich Pflicht)
            if (!cc.getCustomer().isEmailConfirmed()) { skipped++; continue; }

            String unsubscribeUrl = baseUrl + "/mail/unsubscribe"
                    + "?cc=" + cc.getId() + "&t=" + cc.getAuthToken();
            String deleteUrl = baseUrl + "/mail/delete-request"
                    + "?c=" + cc.getCustomer().getId();

            emailService.sendNewsletterMail(
                    cc.getCustomer().getEmail(),
                    shop,                           // für Branding (Logo + Hero-Bild im Header)
                    shop.getEmail(),               // Reply-To = der Laden
                    req.subject(),
                    req.body(),
                    req.imageUrls(),               // optionale Newsletter-Bilder (Liste)
                    unsubscribeUrl,
                    deleteUrl
            );
            sent++;
        }

        // Newsletter im Verlauf speichern (auch wenn 0 Empfänger — dann
        // sieht der Laden trotzdem, dass/was er versendet hat).
        sentNewsletterRepo.save(SentNewsletter.create(
                shop, req.subject(), req.body(), req.imageUrls(), sent));

        return Map.of("sent", sent, "skippedUnconfirmed", skipped);
    }

    // Ein Eintrag im Newsletter-Verlauf
    public record NewsletterHistoryItem(String id, String subject, String body,
                                        List<String> imageUrls, int recipientCount,
                                        String sentAt) {}

    @Operation(summary = "Newsletter-Verlauf (seitenweise, neueste zuerst)")
    @GetMapping("/newsletter/history")
    public Map<String, Object> newsletterHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size,
            Authentication auth) {
        Shop shop = currentShop(auth);
        Page<SentNewsletter> result = sentNewsletterRepo
                .findByShopOrderBySentAtDesc(shop, PageRequest.of(page, size));

        List<NewsletterHistoryItem> items = result.getContent().stream()
                .map(n -> new NewsletterHistoryItem(
                        n.getId(), n.getSubject(), n.getBody(),
                        n.getImageUrls(), n.getRecipientCount(),
                        n.getSentAt().toString()))
                .toList();

        Map<String, Object> map = new HashMap<>();
        map.put("items", items);
        map.put("page", result.getNumber());
        map.put("totalPages", result.getTotalPages());
        map.put("totalItems", result.getTotalElements());
        return map;
    }

    @Operation(summary = "Bild für Newsletter hochladen")
    @PostMapping("/newsletter/image")
    public Map<String, String> uploadNewsletterImage(@org.springframework.web.bind.annotation.RequestBody
                                                     ImageUploadRequest req,
                                                     Authentication auth) {
        Shop shop = currentShop(auth);
        // Eindeutige public_id pro Upload (Shop + Zeitstempel), damit sich
        // mehrere Newsletter-Bilder nicht gegenseitig überschreiben.
        String publicId = "newsletter-" + shop.getId() + "-" + System.currentTimeMillis();
        String url = cloudinaryService.upload(req.base64(), publicId, CloudinaryService.ImageType.NEWSLETTER);
        return Map.of("url", url);
    }
}