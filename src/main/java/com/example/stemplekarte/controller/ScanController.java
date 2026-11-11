package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.StaffTokenFilter;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.service.PointsService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import com.example.stemplekarte.wallet.WalletNotifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "Scan", description = "Stempel vergeben — braucht X-Staff-Token Header")
@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private static final Logger log = LoggerFactory.getLogger(ScanController.class);

    private final CustomerService service;
    private final WalletNotifier notifier;
    private final PointsService pointsService;
    private final com.example.stemplekarte.service.GeoPruefung geoPruefung;
    private final com.example.stemplekarte.service.PaketService paketService;

    // Filialen: welchem Standort ein Scan zugeordnet wird. Optional, damit
    // Tests den Controller ohne diesen Dienst bauen koennen - dann gilt nur
    // die Zuordnung des Zugangs.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.stemplekarte.service.FilialenService filialen;

    // Selbst-Stempeln am Tablet (Rolle KIOSK). Optional wie oben: ohne den
    // Dienst (Tests) gibt es keinen Kiosk-Weg.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.stemplekarte.service.KioskService kiosk;

    // Stille Scanner-Messung (lib/scannerWahl im Frontend). Optional wie oben.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.stemplekarte.service.ScannerMessungService messungen;

    /** Filiale fuers Protokoll: Zuordnung des Zugangs, sonst Geraeteort, sonst Hauptstandort. */
    private String ortFuer(com.example.stemplekarte.model.StaffToken staff, Shop shop, Double lat, Double lon) {
        return filialen != null ? filialen.ortFuer(staff, shop, lat, lon) : staff.getOrtId();
    }

    public ScanController(CustomerService service, WalletNotifier notifier,
                          PointsService pointsService,
                          com.example.stemplekarte.service.GeoPruefung geoPruefung,
                          com.example.stemplekarte.service.PaketService paketService) {
        this.service = service;
        this.notifier = notifier;
        this.pointsService = pointsService;
        this.geoPruefung = geoPruefung;
        this.paketService = paketService;
    }

    @Operation(summary = "Einstellungen des Ladens fuer den Scanner",
            description = "standortPflicht=false: der Laden ist vom Admin befreit, der "
                    + "Scanner fragt dann nicht nach dem Standort. Erfordert X-Staff-Token.")
    @GetMapping("/einstellungen")
    public java.util.Map<String, Object> einstellungen(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal p)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }
        var staff = p.staff();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("standortPflicht", !staff.getShop().isGeoBefreit());
        out.put("rolle", staff.getRolle());
        out.put("kiosk", staff.istKiosk());
        out.put("kioskSperreStunden", staff.getShop().getKioskSperreStunden());
        out.put("label", staff.getLabel());
        out.put("shopName", staff.getShop().getName());
        return out;
    }

    public record MessungRequest(String gegner, Integer altMs, Integer gegnerMs, String aktiv,
                                 String plattform, String geraet,
                                 String lage, Integer bildBreite, Integer bildHoehe, Boolean app) {}

    @Operation(summary = "Eine Runde der stillen Scanner-Messung melden",
            description = "Keine Kundendaten: nur Geraet, Herausforderer und Zeiten. Erfordert X-Staff-Token.")
    @PostMapping("/messung")
    public java.util.Map<String, Object> messung(@RequestBody MessungRequest req, Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal p)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }
        boolean ok = messungen != null && messungen.annehmen(p.staff().getShop().getId(), p.staff().getLabel(),
                req.plattform(), req.geraet(), req.gegner(), req.altMs(), req.gegnerMs(), req.aktiv(),
                req.lage(), req.bildBreite(), req.bildHoehe(), req.app(),
                java.time.Instant.now()) != null;
        return java.util.Map.of("ok", ok);
    }

    public record StateRequest(@NotBlank String qrPayload) {}

    @Operation(summary = "QR aufloesen: welcher Kartentyp, welcher Stand",
            description = "Steuert, welche Oberflaeche der Scanner zeigt. "
                    + "Erfordert X-Staff-Token. POST, weil der QR-Inhalt nichts "
                    + "in einer URL und damit in Server-Logs zu suchen hat.")
    @PostMapping("/state")
    public PointsService.PointsState state(@Valid @RequestBody StateRequest req,
                                           Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal p)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }
        return pointsService.state(req.qrPayload(), p.staff().getShop());
    }

    public record ScanRequest(
            @NotBlank String qrPayload,
            @Min(1) @Max(20) int count,
            // Ort des Geraets. Fehlt er oder liegt er ausserhalb des Radius,
            // wird nicht gestempelt (GeoPruefung.sperre).
            Double lat,
            Double lon
    ) {}

    public record ScanResponse(
            String action, String message, String customerId,
            String cardId, int stamps, int totalRewards,
            int rewardThreshold, int stampsAdded,
            boolean rewardEarned, String rewardText,
            // 10er-Karte: was vom Paket uebrig ist; bei anderen Karten null.
            Integer paketRest
    ) {}

    /** Null bei allen Karten ausser der 10er-Karte. */
    private static Integer paketRest(com.example.stemplekarte.model.CustomerCard cc) {
        return cc.getCard().isPackage() ? cc.getPaketRest() : null;
    }

    @Operation(summary = "QR-Code scannen und Stempel vergeben",
            description = "Erfordert X-Staff-Token Header. count = Anzahl Stempel (1-20)")
    @PostMapping
    public ScanResponse scan(@Valid @RequestBody ScanRequest req, Authentication auth) {
        boolean tokenOk = auth != null && auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal;

        // Eingangsprotokoll: trennt die drei Faelle, die im Scanner identisch
        // aussehen (Token weg / QR unlesbar / Karte unbekannt). Steht vor der
        // Auth-Pruefung, damit auch ein abgelehnter Scan eine Spur hinterlaesst.
        log.info("[SCAN] eingang token={} anzahl={} payload={}",
                tokenOk ? "ok" : "FEHLT", req.count(), req.qrPayload());

        if (!tokenOk) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }

        Shop shop = ((StaffTokenFilter.StaffPrincipal) auth.getPrincipal()).staff().getShop();
        // Gratis-Testphase vorbei: kein Stempel (Laeden ohne Testphase: nie).
        com.example.stemplekarte.service.TarifPruefung.pruefeTestphase(shop);

        // Ohne Ladenort laeuft die Geo-Pruefung ins Leere - dann ist das
        // Stempeln selbst der Hebel, der fehlt.
        if (!com.example.stemplekarte.service.StandortPflicht.darfScannen(shop, java.time.Instant.now())) {
            log.warn("[SCAN] GESPERRT laden={} grund=kein_standort", shop.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    com.example.stemplekarte.service.StandortPflicht.sperrText());
        }

        // Vor dem Stempel: nicht im Laden, kein Stempel.
        String geoSperre = geoPruefung.sperre(shop,
                ((StaffTokenFilter.StaffPrincipal) auth.getPrincipal()).staff().getLabel(),
                req.lat(), req.lon());
        if (geoSperre != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, geoSperre);
        }

        int count = req.count() <= 0 ? 1 : req.count();

        var staff = ((StaffTokenFilter.StaffPrincipal) auth.getPrincipal()).staff();
        String ortId = ortFuer(staff, shop, req.lat(), req.lon());
        ScanResult result;
        // Tablet: immer genau ein Stempel, und nur wenn der KioskService
        // nichts dagegen hat (Kartentyp, volle Karte, Sperrzeit). Pruefung
        // und Stempel laufen dort in EINER Transaktion mit Zeilensperre -
        // sonst geben zwei Tablets im selben Moment zwei Stempel.
        if (staff.istKiosk()) {
            if (kiosk == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        com.example.stemplekarte.model.StaffToken.NUR_PERSONAL_TEXT);
            }
            count = 1;
            result = kiosk.stempeln(shop, req.qrPayload(), java.time.Instant.now(), staff.getLabel(), ortId);
        } else {
            result = service.processScan(req.qrPayload(), shop, count, staff.getLabel(), ortId);
        }
        var cc = result.customerCard();

        // Startpunkt jeder Wallet-Kette. Ab hier laesst sich ein Vorfall
        // vollstaendig verfolgen: nach serial greppen und die [WALLET]-Zeilen
        // der Reihe nach lesen.
        //
        // Nur IDs protokollieren. shop stammt als Lazy-Proxy aus dem
        // StaffTokenFilter; getId() beantwortet der Proxy selbst, getName()
        // wuerde ihn nachladen - und hier ist die Hibernate-Session schon zu.
        // Genau das hat jeden Scan mit einer LazyInitializationException
        // abgebrochen, NACHDEM der Stempel bereits gesetzt war.
        log.info("[WALLET] SCAN serial={} kunde={} karte={} laden={} stempelNeu={} anzahl={}",
                cc.getId(), cc.getCustomer().getId(), cc.getCard().getId(),
                shop.getId(), cc.getStamps(), count);

        // SSE an die offene Kartenseite, stiller APNs-Push, Google-Update.
        // Alle drei abgesichert: ein toter Weg darf den Scan nicht abbrechen,
        // der Stempel ist hier laengst gesetzt.
        boolean rewardEarned = result.rewardsEarnedThisScan() > 0;

        // Google-Mitteilung mit eigenem Text: "Karte voll" schlaegt "Neuer
        // Stempel". Reines Einloesen meldet nichts - der Kunde steht ja an
        // der Kasse. Apple bekommt beides ueber die changeMessages im Pass.
        GoogleWalletService.Meldung meldung = rewardEarned
                ? GoogleWalletService.Meldung.VOLL
                : result instanceof ScanResult.Stamped
                        ? GoogleWalletService.Meldung.STEMPEL
                        : result instanceof ScanResult.Visited
                                ? GoogleWalletService.Meldung.BESUCH
                                : result instanceof ScanResult.Debited
                                        ? GoogleWalletService.Meldung.PAKET
                                        : GoogleWalletService.Meldung.KEINE;
        // Leeres Paket: nichts gebucht, nichts zu melden - der Pass ist unveraendert.
        if (!(result instanceof ScanResult.Empty)) {
            notifier.nachStempelAenderung(cc, meldung);
        }

        String action = switch (result) {
            case ScanResult.Stamped s -> "stamped";
            case ScanResult.Full f -> "full";
            case ScanResult.Redeemed r -> "redeemed";
            case ScanResult.Visited v -> "visited";
            case ScanResult.Debited d -> "debited";
            case ScanResult.Empty e -> "empty";
        };

        return new ScanResponse(
                action, result.message(),
                cc.getCustomer().getId(),
                cc.getCard().getId(),
                cc.getStamps(),
                cc.getTotalRewards(),
                cc.getCard().getRewardThreshold(),
                result instanceof ScanResult.Empty ? 0 : count,
                rewardEarned,
                rewardEarned ? cc.getCard().getRewardText() : null,
                paketRest(cc)
        );
    }

    public record AufladenRequest(@NotBlank String qrPayload, Double lat, Double lon) {}

    @Operation(summary = "Neues Paket auf eine 10er-Karte laden",
            description = "Erfordert X-Staff-Token. Gleiche Standort-Pruefung wie beim Stempeln. "
                    + "Die Paketgroesse der Karte kommt zum Guthaben dazu.")
    @PostMapping("/aufladen")
    public ScanResponse aufladen(@Valid @RequestBody AufladenRequest req, Authentication auth) {
        boolean tokenOk = auth != null && auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal;
        log.info("[SCAN] aufladen eingang token={} payload={}", tokenOk ? "ok" : "FEHLT", req.qrPayload());
        if (!tokenOk) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }
        var staff = ((StaffTokenFilter.StaffPrincipal) auth.getPrincipal()).staff();
        if (staff.istKiosk()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    com.example.stemplekarte.model.StaffToken.NUR_PERSONAL_TEXT);
        }
        Shop shop = staff.getShop();
        com.example.stemplekarte.service.TarifPruefung.pruefeTestphase(shop);
        if (!com.example.stemplekarte.service.StandortPflicht.darfScannen(shop, java.time.Instant.now())) {
            log.warn("[SCAN] GESPERRT laden={} grund=kein_standort", shop.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    com.example.stemplekarte.service.StandortPflicht.sperrText());
        }
        String geoSperre = geoPruefung.sperre(shop, staff.getLabel(), req.lat(), req.lon());
        if (geoSperre != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, geoSperre);
        }

        var cc = paketService.aufladen(req.qrPayload(), shop, staff.getLabel());

        log.info("[WALLET] AUFLADEN serial={} kunde={} karte={} laden={} rest={}",
                cc.getId(), cc.getCustomer().getId(), cc.getCard().getId(), shop.getId(), cc.getPaketRest());

        notifier.nachStempelAenderung(cc, GoogleWalletService.Meldung.AUFGELADEN);

        return new ScanResponse(
                "topup", com.example.stemplekarte.service.PaketService.aufgeladenText(cc.getCard(), cc),
                cc.getCustomer().getId(),
                cc.getCard().getId(),
                cc.getStamps(),
                cc.getTotalRewards(),
                cc.getCard().getRewardThreshold(),
                0,
                false,
                null,
                cc.getPaketRest()
        );
    }

    public record WillkommenRequest(@NotBlank String qrPayload, Double lat, Double lon) {}

    @Operation(summary = "Willkommensgeschenk einloesen und Karte freischalten",
            description = "Erfordert X-Staff-Token. Gleiche Standort-Pruefung wie beim Stempeln. "
                    + "Danach ist die Karte eine normale Stempelkarte mit 0 Stempeln.")
    @PostMapping("/willkommen")
    public ScanResponse willkommen(@Valid @RequestBody WillkommenRequest req, Authentication auth) {
        boolean tokenOk = auth != null && auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal;
        log.info("[SCAN] willkommen eingang token={} payload={}", tokenOk ? "ok" : "FEHLT", req.qrPayload());
        if (!tokenOk) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }

        // Dieselben Sperren wie beim Stempel. Ein Gratis-Artikel ist mehr
        // wert als ein Stempel - von zu Hause darf er erst recht nicht gehen.
        // Bewusst nicht aus scan() herausgezogen: der Stempel-Weg laeuft in
        // echten Laeden und bleibt dafuer unangetastet.
        var staff = ((StaffTokenFilter.StaffPrincipal) auth.getPrincipal()).staff();
        if (staff.istKiosk()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    com.example.stemplekarte.model.StaffToken.NUR_PERSONAL_TEXT);
        }
        Shop shop = staff.getShop();
        com.example.stemplekarte.service.TarifPruefung.pruefeTestphase(shop);
        if (!com.example.stemplekarte.service.StandortPflicht.darfScannen(shop, java.time.Instant.now())) {
            log.warn("[SCAN] GESPERRT laden={} grund=kein_standort", shop.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    com.example.stemplekarte.service.StandortPflicht.sperrText());
        }
        String geoSperre = geoPruefung.sperre(shop, staff.getLabel(), req.lat(), req.lon());
        if (geoSperre != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, geoSperre);
        }

        var cc = service.willkommenEinloesen(req.qrPayload(), shop);

        // Nur IDs - shop ist ein Lazy-Proxy (siehe scan()).
        log.info("[WALLET] WILLKOMMEN serial={} kunde={} karte={} laden={}",
                cc.getId(), cc.getCustomer().getId(), cc.getCard().getId(), shop.getId());

        notifier.nachFreischaltung(cc);

        return new ScanResponse(
                "welcome", "Karte freigeschaltet",
                cc.getCustomer().getId(),
                cc.getCard().getId(),
                cc.getStamps(),
                cc.getTotalRewards(),
                cc.getCard().getRewardThreshold(),
                0,
                false,
                null,
                null
        );
    }

    public record ResetRequest(@NotBlank String qrPayload) {}

    @Operation(summary = "Karte zurücksetzen (Stempel + Belohnungen auf 0)",
            description = "Erfordert X-Staff-Token Header. Setzt die Karte komplett zurück.")
    @PostMapping("/reset")
    public ScanResponse reset(@Valid @RequestBody ResetRequest req, Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }

        var staff = ((StaffTokenFilter.StaffPrincipal) auth.getPrincipal()).staff();
        // Zuruecksetzen = Praemie eingeloest, neue Karte. Das darf eine
        // Aushilfe nicht - sonst gaebe es den Gratis-Artikel ohne Kontrolle.
        if (!staff.darfEinloesen()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    com.example.stemplekarte.model.StaffToken.NUR_MITARBEITER_TEXT);
        }
        Shop shop = staff.getShop();
        var cc = service.resetCard(req.qrPayload(), shop);

        notifier.nachStempelAenderung(cc);

        return new ScanResponse(
                "reset", "Karte zurückgesetzt",
                cc.getCustomer().getId(),
                cc.getCard().getId(),
                cc.getStamps(),
                cc.getTotalRewards(),
                cc.getCard().getRewardThreshold(),
                0,
                false,
                null,
                paketRest(cc)
        );
    }
}