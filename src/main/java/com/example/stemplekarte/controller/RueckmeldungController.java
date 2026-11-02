package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.RueckmeldungService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Rueckmeldungen der Kunden, Seite des Ladens. Zugang wie ueberall unter
 * /api/shop/** ueber das JWT (ROLE_SHOP in der SecurityConfig).
 */
@RestController
@RequestMapping("/api/shop/rueckmeldungen")
public class RueckmeldungController {

    private final RueckmeldungService service;

    public RueckmeldungController(RueckmeldungService service) {
        this.service = service;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    @Operation(summary = "Rueckmeldungen des Ladens",
            description = "Ob eingeschaltet, Schnitt und Anzahl der letzten 30 Tage, "
                    + "ungelesene, und die letzten 50 Rueckmeldungen.")
    @GetMapping
    public RueckmeldungService.Stand stand(Authentication auth) {
        return service.stand(currentShop(auth), Instant.now());
    }

    @Operation(summary = "Rueckmeldungen ein- oder ausschalten",
            description = "Aus = kein Link auf Kartenseite und Wallet-Karte, die Seite nimmt nichts an.")
    @PutMapping("/aktiv")
    public Map<String, Boolean> schalten(Authentication auth, @RequestBody Map<String, Object> body) {
        boolean aktiv = Boolean.TRUE.equals(body.get("aktiv"));
        return Map.of("aktiv", service.schalten(currentShop(auth), aktiv));
    }

    @PutMapping("/{id}/gelesen")
    public Map<String, Boolean> gelesen(Authentication auth, @PathVariable String id) {
        service.gelesen(currentShop(auth), id);
        return Map.of("gelesen", true);
    }
}
