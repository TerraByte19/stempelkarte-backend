package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.ApiKeyFilter;
import com.example.stemplekarte.service.ApiV1Service;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.service.PointsService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import com.example.stemplekarte.wallet.WalletNotifier;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Externe API fuer Kassen, CRM und Zapier. Zugang ueber X-Api-Key
 * (ApiKeyFilter, ROLE_API). Stempeln und Buchen laufen ueber dieselben
 * Dienste wie der Scanner - Geschenk-Sperre, Karte voll, Empfehlungsbonus
 * und Webhooks inklusive. Keine Geo-Pruefung: Server zu Server.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiV1Controller {

    private static final Logger log = LoggerFactory.getLogger(ApiV1Controller.class);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,60}");
    static final int COUNT_MAX = 20;

    private final ApiV1Service lesen;
    private final CustomerService customerService;
    private final PointsService pointsService;
    private final WalletNotifier notifier;

    public ApiV1Controller(ApiV1Service lesen, CustomerService customerService,
                           PointsService pointsService, WalletNotifier notifier) {
        this.lesen = lesen;
        this.customerService = customerService;
        this.pointsService = pointsService;
        this.notifier = notifier;
    }

    private ApiKeyFilter.ApiPrincipal api(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof ApiKeyFilter.ApiPrincipal p)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gültiger API-Schlüssel");
        }
        return p;
    }

    /** QR-Inhalt wie vom Scanner, aus den beiden IDs. Nur harmlose Zeichen, nichts vom Aufrufer landet roh im JSON. */
    static String qr(String customerId, String cardId) {
        if (customerId == null || !ID.matcher(customerId).matches()) throw new IllegalArgumentException("customerId fehlt oder ungültig");
        if (cardId == null || !ID.matcher(cardId).matches()) throw new IllegalArgumentException("cardId fehlt oder ungültig");
        return "{\"cid\":\"" + customerId + "\",\"cardId\":\"" + cardId + "\"}";
    }

    // ── Lesen ───────────────────────────────────────────────────────────

    @Operation(summary = "Karten des Ladens")
    @GetMapping("/cards")
    public List<ApiV1Service.KarteView> cards(Authentication auth) {
        return lesen.karten(api(auth).shop());
    }

    @Operation(summary = "Kunde per E-Mail", description = "Mit seinen Karten bei diesem Laden. 404, wenn er hier keine hat.")
    @GetMapping("/customers")
    public ApiV1Service.KundeView customerByEmail(@RequestParam String email, Authentication auth) {
        return lesen.kundeNachEmail(api(auth).shop(), email);
    }

    @Operation(summary = "Kunde per ID")
    @GetMapping("/customers/{customerId}")
    public ApiV1Service.KundeView customer(@PathVariable String customerId, Authentication auth) {
        return lesen.kundeNachId(api(auth).shop(), customerId);
    }

    // ── Stempeln ────────────────────────────────────────────────────────

    public record StampRequest(String customerId, String cardId, Integer count) {}
    public record StampResponse(String action, String message, String customerId, String cardId,
                                int stamps, int totalRewards, int rewardThreshold, int stampsAdded,
                                boolean rewardEarned, String rewardText,
                                // 10er-Karte: Rest des Pakets; sonst null.
                                Integer remaining) {}

    @Operation(summary = "Stempeln", description = "Wie der Scanner: count Stempel (1-20), Karte voll und Einloesen inklusive.")
    @PostMapping("/stamps")
    public StampResponse stamp(@RequestBody StampRequest req, Authentication auth) {
        ApiKeyFilter.ApiPrincipal p = api(auth);
        Shop shop = p.shop();
        int count = req.count() == null || req.count() <= 0 ? 1 : Math.min(COUNT_MAX, req.count());
        ScanResult result = customerService.processScan(qr(req.customerId(), req.cardId()), shop, count, "API: " + p.label());
        var cc = result.customerCard();
        boolean rewardEarned = result.rewardsEarnedThisScan() > 0;
        GoogleWalletService.Meldung meldung = rewardEarned
                ? GoogleWalletService.Meldung.VOLL
                : result instanceof ScanResult.Stamped
                        ? GoogleWalletService.Meldung.STEMPEL
                        : result instanceof ScanResult.Visited
                                ? GoogleWalletService.Meldung.BESUCH
                                : result instanceof ScanResult.Debited
                                        ? GoogleWalletService.Meldung.PAKET
                                        : GoogleWalletService.Meldung.KEINE;
        if (!(result instanceof ScanResult.Empty)) {
            notifier.nachStempelAenderung(cc, meldung);
        }
        log.info("[API] STAMP laden={} kunde={} karte={} anzahl={} via={}",
                shop.getId(), req.customerId(), req.cardId(), count, p.label());
        String action = switch (result) {
            case ScanResult.Stamped s -> "stamped";
            case ScanResult.Full f -> "full";
            case ScanResult.Redeemed r -> "redeemed";
            case ScanResult.Visited v -> "visited";
            case ScanResult.Debited d -> "debited";
            case ScanResult.Empty e -> "empty";
        };
        return new StampResponse(action, result.message(), req.customerId(), req.cardId(),
                cc.getStamps(), cc.getTotalRewards(), cc.getCard().getRewardThreshold(),
                result instanceof ScanResult.Empty ? 0 : count,
                rewardEarned, rewardEarned ? cc.getCard().getRewardText() : null,
                cc.getCard().isPackage() ? cc.getPaketRest() : null);
    }

    // ── Punkte ──────────────────────────────────────────────────────────

    public record PointsRequest(String customerId, String cardId, long amountCents) {}
    public record PointsResponse(String customerCardId, long pointsX100, String pointsText,
                                 String zielName, long fehlendX100, boolean neuesZielErreicht) {}

    @Operation(summary = "Einkauf auf Punktekarte buchen", description = "amountCents = Betrag in Cent.")
    @PostMapping("/points")
    public PointsResponse points(@RequestBody PointsRequest req, Authentication auth) {
        ApiKeyFilter.ApiPrincipal p = api(auth);
        PointsService.PointsResult e = pointsService.earn(qr(req.customerId(), req.cardId()), p.shop(),
                req.amountCents(), "API: " + p.label());
        notifier.nachPunkteAenderung(e.customerCardId(), e.pointsX100(),
                e.ziel() != null ? e.ziel().name() : null, e.fehlendX100());
        if (e.neuesZielErreicht()) notifier.googleKarteVoll(e.customerCardId(), "Praemie verfuegbar");
        log.info("[API] POINTS laden={} kunde={} karte={} betrag={} via={}",
                p.shop().getId(), req.customerId(), req.cardId(), req.amountCents(), p.label());
        return new PointsResponse(e.customerCardId(), e.pointsX100(), e.pointsText(),
                e.ziel() != null ? e.ziel().name() : null, e.fehlendX100(), e.neuesZielErreicht());
    }
}
