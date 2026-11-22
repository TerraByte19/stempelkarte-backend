package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.FilialImportService;
import com.example.stemplekarte.service.OrteService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Weitere Orte des Ladens fuer Naehe-Erinnerung und Geo-Pruefung. Wie viele
 * ein Laden anlegen darf, setzt der Admin je nach Tarif (Shop.maxOrte);
 * 0 = das Dashboard zeigt den Block gar nicht.
 *
 * Tarif Kette: beliebig viele Filialen, das Hauptkonto schaltet sie ein und
 * aus, und sie lassen sich als Liste auf einmal eintragen.
 */
@RestController
@RequestMapping("/api/shop/me/orte")
public class OrteController {

    private final OrteService orteService;
    private final FilialImportService importService;

    public OrteController(OrteService orteService, FilialImportService importService) {
        this.orteService = orteService;
        this.importService = importService;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    public record OrtRequest(String label, Double latitude, Double longitude) {}

    /** Alles optional: was fehlt, bleibt wie es ist. aktiv nur im Tarif Kette. */
    public record OrtAenderung(String label, Double latitude, Double longitude, Boolean aktiv) {}

    public record VorschauRequest(String text) {}

    public record SammelRequest(List<OrteService.Neu> zeilen) {}

    public record HauptRequest(boolean filiale) {}

    public record OrtItem(String id, String label, double latitude, double longitude,
                          String adresse, boolean aktiv) {
        static OrtItem von(ShopOrt o) {
            return new OrtItem(o.getId(), o.getLabel(), o.getLatitude(), o.getLongitude(),
                    o.getAdresse(), o.isAktiv());
        }
    }

    /**
     * max = wie viele weitere Orte erlaubt sind (0 = nicht im Tarif).
     * kette = Tarif Kette: Schalter aktiv und Sammel-Anlage sind da.
     */
    public record OrteStand(int max, List<OrtItem> orte, boolean kette, boolean hauptIstFiliale) {}

    private OrteStand stand(Shop shop, List<ShopOrt> orte) {
        return new OrteStand(OrteService.erlaubt(shop), orte.stream().map(OrtItem::von).toList(),
                OrteService.kette(shop), shop.isHauptIstFiliale());
    }

    @Operation(summary = "Weitere Orte des Ladens (ohne den Hauptort) und wie viele erlaubt sind")
    @GetMapping
    public OrteStand liste(Authentication auth) {
        Shop shop = currentShop(auth);
        return stand(shop, orteService.liste(shop));
    }

    @Operation(summary = "Ort hinzufuegen", description = "Bis zur vom Admin gesetzten Anzahl (hoechstens 9, "
            + "Apple erlaubt zehn pro Karte; Tarif Kette: ohne diese Grenze). "
            + "Wirkt auf bestehende Wallet-Karten erst mit dem naechsten Stempel.")
    @PostMapping
    public OrteStand hinzufuegen(@RequestBody OrtRequest req, Authentication auth) {
        Shop shop = currentShop(auth);
        return stand(shop, orteService.hinzufuegen(shop.getId(), req.label(), req.latitude(), req.longitude()));
    }

    @Operation(summary = "Ort aendern: Name, Lage, ein-/ausschalten",
            description = "Felder, die fehlen, bleiben unveraendert. aktiv gibt es nur im Tarif Kette: "
                    + "eine ausgeschaltete Filiale fehlt in Wallet-Karte und Statistik, ihr Login und "
                    + "ihre Scanner-Geraete sind gesperrt.")
    @PutMapping("/{id}")
    public OrteStand aendern(@PathVariable String id, @RequestBody OrtAenderung req, Authentication auth) {
        Shop shop = currentShop(auth);
        return stand(shop, orteService.aendern(shop.getId(), id,
                req.label(), req.latitude(), req.longitude(), req.aktiv()));
    }

    @Operation(summary = "Tarif Kette: Liste pruefen, speichert nichts",
            description = "Eine Filiale je Zeile: 'Name, Strasse 5, 10115 Berlin' (Ort wird gesucht) oder "
                    + "'Name; 52.52; 13.405'. Hoechstens 40 Zeilen je Aufruf, die Suche dauert rund eine "
                    + "Sekunde je Adresse. Zeilen mit fehler != null muessen korrigiert werden.")
    @PostMapping("/vorschau")
    public List<FilialImportService.Zeile> vorschau(@RequestBody VorschauRequest req, Authentication auth) {
        return importService.vorschau(currentShop(auth), req.text());
    }

    @Operation(summary = "Tarif Kette: gepruefte Liste speichern",
            description = "Alles oder nichts. Neue Filialen sind ausgeschaltet, bis das Hauptkonto sie einschaltet.")
    @PostMapping("/sammel")
    public OrteStand sammel(@RequestBody SammelRequest req, Authentication auth) {
        Shop shop = currentShop(auth);
        return stand(shop, orteService.sammel(shop.getId(), req.zeilen()));
    }

    @Operation(summary = "Tarif Kette: zaehlt der Hauptstandort als Filiale?",
            description = "Nein, wenn der Hauptstandort nur die Zentrale ist.")
    @PutMapping("/haupt")
    public OrteStand haupt(@RequestBody HauptRequest req, Authentication auth) {
        Shop shop = orteService.hauptIstFiliale(currentShop(auth).getId(), req.filiale());
        return stand(shop, orteService.liste(shop));
    }

    @Operation(summary = "Ort entfernen")
    @DeleteMapping("/{id}")
    public OrteStand loeschen(@PathVariable String id, Authentication auth) {
        Shop shop = currentShop(auth);
        return stand(shop, orteService.loeschen(shop.getId(), id));
    }
}
