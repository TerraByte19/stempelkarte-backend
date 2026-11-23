package com.example.stemplekarte.controller;

import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.FilialKorrekturService;
import com.example.stemplekarte.wallet.WalletNotifier;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Unterkonto (Tarif Kette): Stempel korrigieren. Die Karte wird gescannt -
 * es gibt keine Suche und keinen Kundennamen. Jede Korrektur steht mit
 * Filiale und Login im Protokoll.
 */
@RestController
@RequestMapping("/api/filiale/korrektur")
public class FilialeKorrekturController {

    private final FilialKorrekturService service;
    private final WalletNotifier notifier;
    private final com.example.stemplekarte.service.KartenGeltung geltung;

    public FilialeKorrekturController(FilialKorrekturService service, WalletNotifier notifier,
                                      com.example.stemplekarte.service.KartenGeltung geltung) {
        this.service = service;
        this.notifier = notifier;
        this.geltung = geltung;
    }

    public record KarteRequest(@NotBlank String qrPayload) {}
    public record StempelRequest(int stamps) {}

    @Operation(summary = "Karte nachschlagen: Stand ohne Kundenname",
            description = "POST, weil der QR-Inhalt nichts in einer URL und damit in Server-Logs zu suchen hat.")
    @PostMapping("/karte")
    public FilialKorrekturService.Ansicht karte(@Valid @RequestBody KarteRequest req, Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = FilialeController.filiale(auth);
        geltung.pruefe(p.shop(), p.zugang().getOrtId(), req.qrPayload());
        return service.karte(p.shop(), req.qrPayload());
    }

    @Operation(summary = "Stempelstand setzen",
            description = "0 bis Schwelle, nur Stempelkarten, nicht bei offenem Willkommensgeschenk. "
                    + "Kein Besuch, kein Scan in der Statistik. Die Wallet-Karte wird aktualisiert.")
    @PutMapping("/{customerCardId}")
    public FilialKorrekturService.Ansicht korrigieren(@PathVariable String customerCardId,
                                                      @RequestBody StempelRequest req, Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = FilialeController.filiale(auth);
        geltung.pruefeKundenkarte(p.shop(), p.zugang().getOrtId(), customerCardId);
        FilialKorrekturService.Ergebnis e = service.korrigieren(p.shop(), p.zugang(), customerCardId, req.stamps());
        // Stiller Weg wie in der Kundenliste des Inhabers: nur der neue Stand.
        if (e.geaendert()) notifier.nachStempelAenderung(e.cc());
        return e.ansicht();
    }
}
