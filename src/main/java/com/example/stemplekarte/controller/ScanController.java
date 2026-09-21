package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.StaffTokenFilter;
import com.example.stemplekarte.service.CustomerService;
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

    public ScanController(CustomerService service, WalletNotifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    public record ScanRequest(
            @NotBlank String qrPayload,
            @Min(1) @Max(20) int count
    ) {}

    public record ScanResponse(
            String action, String message, String customerId,
            String cardId, int stamps, int totalRewards,
            int rewardThreshold, int stampsAdded,
            boolean rewardEarned, String rewardText
    ) {}

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
        int count = req.count() <= 0 ? 1 : req.count();

        ScanResult result = service.processScan(req.qrPayload(), shop, count);
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
        notifier.nachStempelAenderung(cc);

        boolean rewardEarned = result.rewardsEarnedThisScan() > 0;

        // ── Google Wallet: zusätzliche "Karte voll!"-Benachrichtigung ────
        // Apple bekommt diese extra Nachricht automatisch über das
        // changeMessage am "reward-milestone"-Feld im Pass (siehe
        // ApplePassService) — Google braucht dafür einen expliziten Aufruf.
        if (rewardEarned) {
            notifier.googleKarteVoll(cc.getId(), cc.getCard().getRewardText());
        }

        String action = switch (result) {
            case ScanResult.Stamped s -> "stamped";
            case ScanResult.Full f -> "full";
            case ScanResult.Redeemed r -> "redeemed";
        };

        return new ScanResponse(
                action, result.message(),
                cc.getCustomer().getId(),
                cc.getCard().getId(),
                cc.getStamps(),
                cc.getTotalRewards(),
                cc.getCard().getRewardThreshold(),
                count,
                rewardEarned,
                rewardEarned ? cc.getCard().getRewardText() : null
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

        Shop shop = ((StaffTokenFilter.StaffPrincipal) auth.getPrincipal()).staff().getShop();
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
                null
        );
    }
}