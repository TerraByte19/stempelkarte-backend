package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.Kundengruppe;
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
    private final com.example.stemplekarte.service.PaketService paketService;

    public KundenController(KundenService kundenService, WalletNotifier notifier,
                            com.example.stemplekarte.service.PaketService paketService) {
        this.kundenService = kundenService;
        this.notifier = notifier;
        this.paketService = paketService;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    @Operation(summary = "Kundenliste",
            description = "Eine Zeile pro Kundenkarte (aktive Karten), zuletzt gesehene zuerst. "
                    + "q sucht in Name und E-Mail, gruppe (NEU, STAMMGAST, GELEGENTLICH, WIRD_RUHIG, VERLOREN) filtert.")
    @GetMapping
    public KundenService.Seite liste(@RequestParam(defaultValue = "") String q,
                                     @RequestParam(defaultValue = "") String gruppe,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size,
                                     Authentication auth) {
        return kundenService.suche(currentShop(auth), q, Kundengruppe.von(gruppe), page, size);
    }

    @Operation(summary = "Kundengruppen", description = "Kundenkarten je Gruppe und die Regeln des Ladens.")
    @GetMapping("/gruppen")
    public KundenService.Gruppen gruppen(Authentication auth) {
        return kundenService.gruppen(currentShop(auth));
    }

    public record RegelnRequest(int stammAb, int ruhigTage, int verlorenTage) {}

    @Operation(summary = "Regeln der Kundengruppen setzen",
            description = "Stammgast ab n Besuchen, wird ruhig nach n Tagen, verloren nach n Tagen (groesser als ruhig).")
    @PutMapping("/gruppen")
    public KundenService.Gruppen regeln(@RequestBody RegelnRequest req, Authentication auth) {
        Shop shop = kundenService.updateRegeln(currentShop(auth).getId(),
                new Kundengruppe.Regeln(req.stammAb(), req.ruhigTage(), req.verlorenTage()));
        return kundenService.gruppen(shop);
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

    public record PaketRequest(int rest) {}

    @Operation(summary = "Guthaben einer 10er-Karte setzen",
            description = "0 bis 500, nur 10er-Karten. Kein Besuch, kein gekauftes Paket. Die Wallet-Karte wird aktualisiert.")
    @PutMapping("/{customerCardId}/paket")
    public KundenService.Kunde paket(@PathVariable String customerCardId,
                                     @RequestBody PaketRequest req,
                                     Authentication auth) {
        CustomerCard cc = kundenService.korrigierePaket(currentShop(auth), customerCardId, req.rest());
        notifier.nachStempelAenderung(cc);
        return kundenService.kunde(cc);
    }

    @Operation(summary = "Neues Paket auf eine 10er-Karte laden",
            description = "Wie an der Kasse: Paketgroesse der Karte wird dem Guthaben hinzugefuegt, Mitteilung aufs Handy.")
    @org.springframework.web.bind.annotation.PostMapping("/{customerCardId}/aufladen")
    public KundenService.Kunde aufladen(@PathVariable String customerCardId, Authentication auth) {
        CustomerCard cc = paketService.aufladen(currentShop(auth), customerCardId);
        notifier.nachStempelAenderung(cc, com.example.stemplekarte.wallet.GoogleWalletService.Meldung.AUFGELADEN);
        return kundenService.kunde(cc);
    }
}
