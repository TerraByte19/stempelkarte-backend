package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.*;
import com.example.stemplekarte.repository.CardRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Shop", description = "Shop-Profil und Karten-Verwaltung")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/shop")
public class ShopController {

    // Alle Laeden sind in Deutschland - Statistik-Zeitzone fest. (Der Server
    // laeuft auf Render in UTC; ZoneId.systemDefault() verschob "beste Stunde"
    // um 1-2 h und schob Mitternachts-Scans auf den falschen Tag.)
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final ShopService shopService;
    private final CardService cardService;
    private final CardRepository cardRepo;
    private final CustomerCardRepository customerCardRepo;
    private final CloudinaryService cloudinaryService;
    private final EmailService emailService;
    private final SentNewsletterRepository sentNewsletterRepo;
    private final ScanLogRepository scanLogRepo;

    @Value("${stempelkarte.base-url:http://localhost:8080}")
    private String baseUrl;

    public ShopController(ShopService shopService, CardService cardService,
                          CardRepository cardRepo,
                          CustomerCardRepository customerCardRepo, CloudinaryService cloudinaryService,
                          EmailService emailService, SentNewsletterRepository sentNewsletterRepo,
                          ScanLogRepository scanLogRepo) {
        this.shopService = shopService;
        this.cardService = cardService;
        this.cardRepo = cardRepo;
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
        map.put("logoOriginalUrl", shop.getLogoOriginalUrl() != null ? shop.getLogoOriginalUrl() : "");
        map.put("heroOriginalUrl", shop.getHeroOriginalUrl() != null ? shop.getHeroOriginalUrl() : "");
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
        // ALLE Karten (auch deaktivierte) fuer die Lebenszeit-Summen - sonst
        // verschwinden Kunden/Stempel, sobald ein Laden eine Karte "loescht"
        // (= deaktiviert). Die perCard-Liste unten zeigt nur die aktiven.
        List<Card> alleKarten = cardRepo.findByShop(shop);

        // "vergeben" = alle je vergebenen Stempel. CustomerCard.stamps wird beim
        // Einloesen auf 0 gesetzt; die eingeloesten Stempel rechnen wir ueber
        // totalRewards*threshold zurueck. offeneStempel = aktueller Wallet-Stand
        // (kann sinken - das ist eine Verbindlichkeit, keine Aktivitaet).
        long stampsGranted = 0;
        long openStamps = 0;
        long einloesungen = 0;
        long cardRows = 0;              // customer_card-Zeilen (fuer Fuellgrad-Schnitt)

        int customersNearReward = 0;   // Karten >= 80% und noch nicht am Ziel
        int customersWithConsent = 0;  // customer_card-Zeilen mit Marketing-Einwilligung
        double fillSum = 0;            // Summe der Fuellgrade (pro customer_card)

        Instant thirtyDaysAgo = Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS);
        Instant sevenDaysAgo  = Instant.now().minus(7,  java.time.temporal.ChronoUnit.DAYS);
        Instant fourteenDaysAgo = Instant.now().minus(14, java.time.temporal.ChronoUnit.DAYS);

        List<Map<String, Object>> perCard = new java.util.ArrayList<>();
        int newThisWeek = 0, newLastWeek = 0;

        for (Card card : alleKarten) {
            List<CustomerCard> ccs = customerCardRepo.findByCard(card);
            int threshold = Math.max(1, card.getRewardThreshold());
            int stamps = ccs.stream().mapToInt(CustomerCard::getStamps).sum();
            int rewards = ccs.stream().mapToInt(CustomerCard::getTotalRewards).sum();

            openStamps    += stamps;
            einloesungen  += rewards;
            stampsGranted += (long) stamps + (long) rewards * threshold;
            cardRows      += ccs.size();

            for (CustomerCard cc : ccs) {
                if (cc.getStamps() >= threshold * 0.8 && cc.getStamps() < threshold) customersNearReward++;
                if (cc.isMarketingConsent()) customersWithConsent++;
                fillSum += Math.min(1.0, (double) cc.getStamps() / threshold);

                Instant created = cc.getCreatedAt();
                if (created != null) {
                    if (created.isAfter(sevenDaysAgo)) newThisWeek++;
                    else if (created.isAfter(fourteenDaysAgo)) newLastWeek++;
                }
            }

            if (card.isActive()) {
                Map<String, Object> cardMap = new HashMap<>();
                cardMap.put("cardId", card.getId());
                cardMap.put("cardName", card.getName());
                cardMap.put("customerCount", ccs.size());
                cardMap.put("totalStamps", stamps);
                cardMap.put("totalRewards", rewards);
                cardMap.put("rewardThreshold", card.getRewardThreshold());
                perCard.add(cardMap);
            }
        }

        // Personen statt customer_card-Zeilen (bei mehreren Karten pro Laden
        // sonst dieselbe Person mehrfach).
        long customerCount = customerCardRepo.countDistinctCustomersByShop(shop);
        long customersWithReward = customerCardRepo.countDistinctCustomersWithRewardByShop(shop);
        // "aktiv" ehrlich: mindestens ein Scan in 30 Tagen (nicht: Karte in den
        // 30 Tagen angelegt - das war der alte updatedAt-Weg).
        long activeCustomers30d = scanLogRepo.countDistinctCustomersSince(shop.getId(), thirtyDaysAgo);

        double avgStampsPerCustomer = customerCount > 0
                ? Math.round((double) stampsGranted / customerCount * 10) / 10.0 : 0;
        int redemptionRate = customerCount > 0
                ? (int) Math.round((double) customersWithReward / customerCount * 100) : 0;
        int avgFillPercent = cardRows > 0
                ? (int) Math.round(fillSum / cardRows * 100) : 0;

        // 30-Tage-Verlauf aus dem ScanLog
        List<ScanLog> recent = scanLogRepo
                .findByShopIdAndScannedAtAfterOrderByScannedAtAsc(shop.getId(), thirtyDaysAgo);

        int stampsThisWeek = 0, stampsThisMonth = 0;
        int rewardsThisWeek = 0, rewardsThisMonth = 0;
        int[] byWeekday = new int[8];   // Index 1-7
        int[] byHour = new int[24];

        for (ScanLog sl : recent) {
            stampsThisMonth += sl.getStampsAdded();
            rewardsThisMonth += sl.getRewardsEarned();
            if (sl.getScannedAt().isAfter(sevenDaysAgo)) {
                stampsThisWeek += sl.getStampsAdded();
                rewardsThisWeek += sl.getRewardsEarned();
            }
            var zdt = sl.getScannedAt().atZone(ZONE);
            byWeekday[zdt.getDayOfWeek().getValue()] += sl.getStampsAdded();
            byHour[zdt.getHour()] += sl.getStampsAdded();
        }

        String[] dayNames = {"", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"};
        String bestDay = null;
        int bestDayCount = 0;
        for (int i = 1; i <= 7; i++) if (byWeekday[i] > bestDayCount) { bestDayCount = byWeekday[i]; bestDay = dayNames[i]; }
        int bestHour = -1, bestHourCount = 0;
        for (int h = 0; h < 24; h++) if (byHour[h] > bestHourCount) { bestHourCount = byHour[h]; bestHour = h; }

        // Verlauf: LUECKENLOS - jeder Tag der letzten 30, auch ohne Scans (0).
        // (Vorher fehlten scanlose Tage; das Frontend zeichnete sie als
        // schraege Linie statt als Nulllinie.)
        java.util.Map<String, int[]> byDay = new java.util.TreeMap<>();
        java.time.LocalDate von = thirtyDaysAgo.atZone(ZONE).toLocalDate();
        java.time.LocalDate bis = Instant.now().atZone(ZONE).toLocalDate();
        for (java.time.LocalDate d = von; !d.isAfter(bis); d = d.plusDays(1)) {
            byDay.put(d.toString(), new int[2]);
        }
        for (ScanLog sl : recent) {
            String day = sl.getScannedAt().atZone(ZONE).toLocalDate().toString();
            int[] v = byDay.get(day);
            if (v == null) { v = new int[2]; byDay.put(day, v); }
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
        summary.put("totalCards", cardService.getByShop(shop).size());  // nur aktive
        summary.put("totalCustomers", customerCount);                   // Personen
        summary.put("totalStamps", stampsGranted);                      // je vergeben (Lebenszeit)
        summary.put("openStamps", openStamps);                          // aktueller Wallet-Stand
        summary.put("totalRewards", einloesungen);                      // Einloesungen
        summary.put("avgStampsPerCustomer", avgStampsPerCustomer);
        summary.put("redemptionRate", redemptionRate);
        summary.put("customersNearReward", customersNearReward);
        summary.put("avgFillPercent", avgFillPercent);
        summary.put("customersWithConsent", customersWithConsent);
        summary.put("activeCustomers30d", activeCustomers30d);
        summary.put("stampsThisWeek", stampsThisWeek);
        summary.put("stampsThisMonth", stampsThisMonth);
        summary.put("rewardsThisWeek", rewardsThisWeek);
        summary.put("rewardsThisMonth", rewardsThisMonth);
        summary.put("bestDay", bestDay);
        summary.put("bestHour", bestHour);
        summary.put("newCustomersThisWeek", newThisWeek);
        summary.put("newCustomersLastWeek", newLastWeek);
        summary.put("perCard", perCard);
        summary.put("history", history);

        // Stempel je Wochentag (Mo..So) und je Stunde (0..23) ueber die 30 Tage
        // - fuer "ruhigster Tag" und die Stunden-Uebersicht im Frontend.
        int[] byWeekdayOut = new int[7];
        for (int i = 1; i <= 7; i++) byWeekdayOut[i - 1] = byWeekday[i];
        summary.put("byWeekday", byWeekdayOut);
        summary.put("byHour", byHour);
        return summary;
    }

    // Tages-Detail: Stunden-Verteilung der Stempel fuer EINEN Tag. Wird
    // aufgerufen, wenn im Verlaufs-Diagramm ein Balken angetippt wird.
    @Operation(summary = "Stempel eines einzelnen Tages nach Stunde")
    @GetMapping("/stats/day")
    public Map<String, Object> statsDay(@RequestParam String date, Authentication auth) {
        Shop shop = currentShop(auth);
        java.time.LocalDate d;
        try {
            d = java.time.LocalDate.parse(date);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungueltiges Datum (erwartet JJJJ-MM-TT)");
        }
        Instant from = d.atStartOfDay(ZONE).toInstant();
        Instant to = d.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<ScanLog> logs = scanLogRepo
                .findByShopIdAndScannedAtBetweenOrderByScannedAtAsc(shop.getId(), from, to);

        int[] byHour = new int[24];
        int stamps = 0, rewards = 0;
        for (ScanLog sl : logs) {
            int h = sl.getScannedAt().atZone(ZONE).getHour();
            byHour[h] += sl.getStampsAdded();
            stamps += sl.getStampsAdded();
            rewards += sl.getRewardsEarned();
        }

        Map<String, Object> out = new HashMap<>();
        out.put("date", date);
        out.put("byHour", byHour);
        out.put("stamps", stamps);
        out.put("rewards", rewards);
        return out;
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