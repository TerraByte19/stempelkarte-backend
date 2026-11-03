package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.AutoNachrichtTyp;
import com.example.stemplekarte.service.AutoNachrichtenService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Automatische Nachrichten des Ladens (Willkommen, zweiter Besuch, fast
 * voll, Praemie bereit, VIP). Zugang ueber das JWT wie ShopController.
 */
@RestController
@RequestMapping("/api/shop/auto-nachrichten")
public class AutoNachrichtenController {

    private final AutoNachrichtenService dienst;

    public AutoNachrichtenController(AutoNachrichtenService dienst) {
        this.dienst = dienst;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    @Operation(summary = "Automatische Nachrichten",
            description = "Fuenf Eintraege (WILLKOMMEN, ZWEITER_BESUCH, FAST_VOLL, PRAEMIE_BEREIT, VIP): "
                    + "Schalter, eigener Text (null = Standard) und Standardtext. "
                    + "Platzhalter im Text: {name}, {offen}, {praemie}.")
    @GetMapping
    public List<AutoNachrichtenService.Eintrag> liste(Authentication auth) {
        return dienst.liste(currentShop(auth));
    }

    public record EinstellenRequest(boolean aktiv, String text) {}

    @Operation(summary = "Automat ein-/ausschalten und Text setzen",
            description = "text leer = Standardtext. Hoechstens 160 Zeichen.")
    @PutMapping("/{typ}")
    public AutoNachrichtenService.Eintrag einstellen(@PathVariable String typ,
                                                     @RequestBody EinstellenRequest req,
                                                     Authentication auth) {
        return dienst.einstellen(currentShop(auth).getId(), AutoNachrichtTyp.von(typ), req.aktiv(), req.text());
    }
}
