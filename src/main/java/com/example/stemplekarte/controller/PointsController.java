package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.StaffTokenFilter;
import com.example.stemplekarte.service.PointsService;
import com.example.stemplekarte.wallet.WalletNotifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Punktebuchungen. Alle Routen brauchen den X-Staff-Token-Header, wie
 * /api/scan.
 *
 * POST auch beim Abfragen des Zustands: der QR-Inhalt hat nichts in einer
 * URL und damit in Server-Logs zu suchen.
 */
@Tag(name = "Punkte", description = "Punkte buchen und einloesen - braucht X-Staff-Token")
@RestController
@RequestMapping("/api/points")
public class PointsController {

    private final PointsService service;
    private final WalletNotifier notifier;

    public PointsController(PointsService service, WalletNotifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    // ── Anfragen ──────────────────────────────────────────────────────────

    public record EarnRequest(@NotBlank String qrPayload, long amountCents) {}
    public record RedeemRequest(@NotBlank String qrPayload, @NotBlank String rewardId) {}
    public record CorrectRequest(@NotBlank String qrPayload, Long amountCents, Long pointsX100) {}
    public record UndoRequest(@NotBlank String qrPayload, @NotBlank String bookingId) {}

    // ── Antwort ───────────────────────────────────────────────────────────
    //
    // Die Ansichtstypen liegen im PointsService und werden dort INNERHALB
    // der Transaktion gefuellt. Der Controller reicht sie nur weiter und
    // fasst selbst keine Entity mehr an - mit open-in-view: false wuerde
    // jeder Zugriff hier auf eine geschlossene Session treffen.

    public record PointsResponse(String customerCardId, String customerName,
                                 String cardId, String cardName,
                                 long pointsX100, String pointsText,
                                 String zielName, long fehlendX100, String fehlendText,
                                 boolean neuesZielErreicht,
                                 List<PointsService.RewardView> katalog,
                                 PointsService.BookingView letzteBuchung) {}

    // ── Routen ────────────────────────────────────────────────────────────

    @Operation(summary = "Einkauf buchen")
    @PostMapping("/earn")
    public PointsResponse earn(@Valid @RequestBody EarnRequest req, Authentication auth) {
        Staff staff = staffAus(auth);
        var ergebnis = service.earn(req.qrPayload(), staff.shop(), req.amountCents(), staff.label());
        melden(ergebnis);
        return antwort(ergebnis);
    }

    @Operation(summary = "Praemie einloesen")
    @PostMapping("/redeem")
    public PointsResponse redeem(@Valid @RequestBody RedeemRequest req, Authentication auth) {
        Staff staff = staffAus(auth);
        var ergebnis = service.redeem(req.qrPayload(), staff.shop(), req.rewardId(), staff.label());
        melden(ergebnis);
        return antwort(ergebnis);
    }

    @Operation(summary = "Punkte von Hand korrigieren")
    @PostMapping("/correct")
    public PointsResponse correct(@Valid @RequestBody CorrectRequest req, Authentication auth) {
        Staff staff = staffAus(auth);
        var ergebnis = service.correct(req.qrPayload(), staff.shop(),
                req.amountCents(), req.pointsX100(), staff.label());
        melden(ergebnis);
        return antwort(ergebnis);
    }

    @Operation(summary = "Buchung zuruecknehmen")
    @PostMapping("/undo")
    public PointsResponse undo(@Valid @RequestBody UndoRequest req, Authentication auth) {
        Staff staff = staffAus(auth);
        var ergebnis = service.undo(req.qrPayload(), staff.shop(), req.bookingId(), staff.label());
        melden(ergebnis);
        return antwort(ergebnis);
    }

    // ── Helfer ────────────────────────────────────────────────────────────

    /** Laden und Geraetename. Das Token selbst bleibt hier - sein Wert ist
     *  die Zugangsberechtigung und hat in keiner Antwort etwas verloren. */
    private record Staff(Shop shop, String label) {}

    private Staff staffAus(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal p)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }
        return new Staff(p.staff().getShop(), p.staff().getLabel());
    }

    private void melden(PointsService.PointsResult e) {
        notifier.nachPunkteAenderung(e.customerCardId(), e.pointsX100(),
                e.ziel() != null ? e.ziel().name() : null, e.fehlendX100());
        if (e.neuesZielErreicht()) {
            notifier.googleKarteVoll(e.customerCardId(), "Praemie verfuegbar");
        }
    }

    /**
     * Reines Umhaengen. Kein Datenbankzugriff, kein Entity-Zugriff: alles
     * ist im Dienst innerhalb der Transaktion fertig abgebildet worden.
     */
    private PointsResponse antwort(PointsService.PointsResult e) {
        return new PointsResponse(
                e.customerCardId(), e.customerName(),
                e.cardId(), e.cardName(),
                e.pointsX100(), e.pointsText(),
                e.ziel() != null ? e.ziel().name() : null,
                e.fehlendX100(), e.fehlendText(),
                e.neuesZielErreicht(),
                e.katalog(), e.booking());
    }
}
