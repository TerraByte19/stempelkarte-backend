package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.*;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.SentNewsletterRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.EmailService;
import com.example.stemplekarte.service.NewsletterService;
import com.example.stemplekarte.service.ShopService;
import com.example.stemplekarte.service.StatsService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    private final StatsService statsService;
    private final NewsletterService newsletterService;

    @Value("${stempelkarte.base-url:http://localhost:8080}")
    private String baseUrl;

    public ShopController(ShopService shopService, CardService cardService,
                          CustomerCardRepository customerCardRepo, CloudinaryService cloudinaryService,
                          EmailService emailService, SentNewsletterRepository sentNewsletterRepo,
                          StatsService statsService, NewsletterService newsletterService) {
        this.shopService = shopService;
        this.cardService = cardService;
        this.customerCardRepo = customerCardRepo;
        this.cloudinaryService = cloudinaryService;
        this.emailService = emailService;
        this.sentNewsletterRepo = sentNewsletterRepo;
        this.statsService = statsService;
        this.newsletterService = newsletterService;
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
        map.put("logoOriginalUrl", shop.getLogoOriginalUrl() != null ? shop.getLogoOriginalUrl() : "");
        map.put("heroOriginalUrl", shop.getHeroOriginalUrl() != null ? shop.getHeroOriginalUrl() : "");
        // Sperrbildschirm-Erinnerung. lockScreenActive sagt, ob sie WIRKLICH
        // greift - eingeschaltet ohne Koordinaten tut nichts.
        map.put("lockScreenEnabled", Boolean.TRUE.equals(shop.getLockScreenEnabled()));
        map.put("lockScreenActive", shop.isLockScreenActive());
        map.put("latitude", shop.getLatitude());
        map.put("longitude", shop.getLongitude());
        map.put("lockScreenTextFull", shop.getLockScreenTextFull());
        map.put("lockScreenTextProgress", shop.getLockScreenTextProgress());
        return map;
    }

    public record LockScreenRequest(Boolean enabled, Double latitude, Double longitude,
                                    String textFull, String textProgress) {}

    @Operation(summary = "Sperrbildschirm-Erinnerung ein-/ausschalten",
            description = "Ist die Karte voll, bietet iOS sie in Ladennaehe auf dem "
                    + "Sperrbildschirm an. Braucht die Koordinaten des Ladens. Wirkt auf "
                    + "bestehende Wallet-Karten erst mit dem naechsten Stempel.")
    @PutMapping("/me/lockscreen")
    public Map<String, Object> updateLockScreen(@RequestBody LockScreenRequest req,
                                                Authentication auth) {
        Shop shop = currentShop(auth);
        Shop updated = shopService.updateLockScreen(
                shop.getId(), req.enabled(), req.latitude(), req.longitude());
        updated = shopService.updateLockScreenTexts(
                shop.getId(), req.textFull(), req.textProgress());
        Map<String, Object> out = new HashMap<>();
        out.put("lockScreenEnabled", Boolean.TRUE.equals(updated.getLockScreenEnabled()));
        out.put("lockScreenActive", updated.isLockScreenActive());
        out.put("latitude", updated.getLatitude());
        out.put("longitude", updated.getLongitude());
        out.put("lockScreenTextFull", updated.getLockScreenTextFull());
        out.put("lockScreenTextProgress", updated.getLockScreenTextProgress());
        return out;
    }

    public record ExcludeSundayRequest(boolean enabled) {}

    @Operation(summary = "Sonntag aus der Statistik ausschliessen (Ø/Tag, staerkster/ruhigster Tag)",
            description = "Wirkt auf /stats/summary - auch die Ansicht im Admin-Panel, die "
                    + "denselben Wert nur anzeigt, ohne eigenen Schalter.")
    @PutMapping("/stats/exclude-sunday")
    public Map<String, Object> updateExcludeSunday(@RequestBody ExcludeSundayRequest req,
                                                    Authentication auth) {
        Shop shop = currentShop(auth);
        Shop updated = shopService.updateExcludeSundayFromStats(shop.getId(), req.enabled());
        return Map.of("excludeSunday", Boolean.TRUE.equals(updated.getExcludeSundayFromStats()));
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
        return statsService.summary(currentShop(auth));
    }

    // Tages-Detail: Stunden-Verteilung der Stempel fuer EINEN Tag. Wird
    // aufgerufen, wenn im Verlaufs-Diagramm ein Balken angetippt wird.
    @Operation(summary = "Stempel eines einzelnen Tages nach Stunde")
    @GetMapping("/stats/day")
    public Map<String, Object> statsDay(@RequestParam String date, Authentication auth) {
        Shop shop = currentShop(auth);
        try {
            return statsService.day(shop, java.time.LocalDate.parse(date));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungueltiges Datum (erwartet JJJJ-MM-TT)");
        }
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
        List<CustomerCard> empfaenger = newsletterService.empfaenger(shop);
        // Bestätigte (Double-Opt-In) zählen separat, nur die bekommen wirklich Mails
        long confirmed = empfaenger.stream()
                .filter(cc -> cc.getCustomer().isEmailConfirmed())
                .count();
        return Map.of(
                "total", empfaenger.size(),
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

        // Eine einzige Mail — die kann direkt gesendet werden, und der Laden
        // erfaehrt sofort, ob sie rausging. Genau dafuer ist der Test da.
        boolean ok = emailService.sendNewsletterMail(
                shop.getEmail(),               // nur an den Laden selbst
                shop,                          // Branding (Logo + Hero-Bild)
                shop.getEmail(),               // Reply-To
                "[TEST] " + req.subject(),     // Betreff als Test markiert
                req.body(),
                req.imageUrls(),
                unsubscribeUrl,
                deleteUrl
        );

        return Map.of("sentTo", shop.getEmail(), "ok", ok);
    }

    @Operation(summary = "Newsletter an alle Kunden mit Einwilligung versenden")
    @PostMapping("/newsletter")
    public Map<String, Object> sendNewsletter(@Valid @org.springframework.web.bind.annotation.RequestBody
                                              NewsletterRequest req,
                                              Authentication auth) {
        Shop shop = currentShop(auth);

        int alle = newsletterService.empfaenger(shop).size();
        // Double-Opt-In: nur an bestätigte E-Mails senden (gesetzlich Pflicht)
        List<NewsletterService.Empfaenger> empfaenger = newsletterService.bestaetigteEmpfaenger(shop);
        int skipped = alle - empfaenger.size();

        // Verlaufs-Eintrag zuerst anlegen (auch bei 0 Empfängern — dann sieht
        // der Laden trotzdem, dass/was er versendet hat), dann im Hintergrund
        // senden. Synchron zu senden wuerde bei mehreren hundert Empfaengern
        // in den Gateway-Timeout laufen.
        SentNewsletter eintrag = sentNewsletterRepo.save(
                SentNewsletter.starte(shop, req.subject(), req.body(), req.imageUrls()));

        newsletterService.versendeImHintergrund(eintrag.getId(), shop, empfaenger,
                req.subject(), req.body(), req.imageUrls());

        // "queued", nicht "sent": zum Zeitpunkt der Antwort ist noch keine
        // Mail draussen. Das echte Ergebnis steht danach im Verlauf.
        return Map.of("queued", empfaenger.size(),
                "skippedUnconfirmed", skipped,
                "newsletterId", eintrag.getId());
    }

    // Ein Eintrag im Newsletter-Verlauf. recipientCount = tatsaechlich
    // zugestellte Mails, status = RUNNING solange der Versand laeuft.
    public record NewsletterHistoryItem(String id, String subject, String body,
                                        List<String> imageUrls, int recipientCount,
                                        int failedCount, String status, String failedSample,
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
                        n.getFailedCount(), n.getStatus(), n.getFailedSample(),
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