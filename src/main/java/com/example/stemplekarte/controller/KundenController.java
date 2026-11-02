package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.KundenService;
import com.example.stemplekarte.wallet.WalletNotifier;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kundenliste des Ladens. Zugang wie ShopController ueber das JWT
 * (/api/shop/** verlangt ROLE_SHOP in der SecurityConfig).
 */
@RestController
@RequestMapping("/api/shop/kunden")
public class KundenController {

    private final KundenService kundenService;
    private final WalletNotifier notifier;

    public KundenController(KundenService kundenService, WalletNotifier notifier) {
        this.kundenService = kundenService;
        this.notifier = notifier;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    @Operation(summary = "Kundenliste",
            description = "Eine Zeile pro Kundenkarte (aktive Karten), zuletzt gesehene zuerst. "
                    + "q sucht in Name und E-Mail.")
    @GetMapping
    public KundenService.Seite liste(@RequestParam(defaultValue = "") String q,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size,
                                     Authentication auth) {
        return kundenService.suche(currentShop(auth), q, page, size);
    }

    public record StempelRequest(int stamps) {}

    @Operation(summary = "Stempelstand einer Kundenkarte setzen",
            description = "0 bis Schwelle, nur Stempelkarten, nicht bei offenem Willkommensgeschenk. "
                    + "Kein Besuch, kein Scan in der Statistik. Die Wallet-Karte wird aktualisiert.")
    @PutMapping("/{customerCardId}/stempel")
    public KundenService.Kunde stempel(@PathVariable String customerCardId,
                                       @RequestBody StempelRequest req,
                                       Authentication auth) {
        CustomerCard cc = kundenService.korrigiereStempel(currentShop(auth), customerCardId, req.stamps());
        // Stiller Weg: Apple meldet ueber das changeMessage im Pass "Update!
        // Dein Stempelstand", Google bekommt nur den neuen Stand - "Neuer
        // Stempel" waere bei einer Korrektur nach unten falsch.
        notifier.nachStempelAenderung(cc);
        return kundenService.kunde(cc);
    }
}
