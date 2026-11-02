package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.OrteService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Weitere Orte des Ladens fuer Naehe-Erinnerung und Geo-Pruefung. Wie viele
 * ein Laden anlegen darf, setzt der Admin je nach Tarif (Shop.maxOrte);
 * 0 = das Dashboard zeigt den Block gar nicht.
 */
@RestController
@RequestMapping("/api/shop/me/orte")
public class OrteController {

    private final OrteService orteService;

    public OrteController(OrteService orteService) {
        this.orteService = orteService;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    public record OrtRequest(String label, Double latitude, Double longitude) {}

    public record OrtItem(String id, String label, double latitude, double longitude) {
        static OrtItem von(ShopOrt o) {
            return new OrtItem(o.getId(), o.getLabel(), o.getLatitude(), o.getLongitude());
        }
    }

    /** max = vom Admin freigeschaltet (0 = nicht im Tarif). */
    public record OrteStand(int max, List<OrtItem> orte) {}

    private OrteStand stand(Shop shop, List<ShopOrt> orte) {
        return new OrteStand(OrteService.erlaubt(shop), orte.stream().map(OrtItem::von).toList());
    }

    @Operation(summary = "Weitere Orte des Ladens (ohne den Hauptort) und wie viele erlaubt sind")
    @GetMapping
    public OrteStand liste(Authentication auth) {
        Shop shop = currentShop(auth);
        return stand(shop, orteService.liste(shop));
    }

    @Operation(summary = "Ort hinzufuegen", description = "Bis zur vom Admin gesetzten Anzahl (hoechstens 9, "
            + "Apple erlaubt zehn pro Karte). Wirkt auf bestehende Wallet-Karten erst mit dem naechsten Stempel.")
    @PostMapping
    public OrteStand hinzufuegen(@RequestBody OrtRequest req, Authentication auth) {
        Shop shop = currentShop(auth);
        return stand(shop, orteService.hinzufuegen(shop.getId(), req.label(), req.latitude(), req.longitude()));
    }

    @Operation(summary = "Ort entfernen")
    @DeleteMapping("/{id}")
    public OrteStand loeschen(@PathVariable String id, Authentication auth) {
        Shop shop = currentShop(auth);
        return stand(shop, orteService.loeschen(shop.getId(), id));
    }
}
