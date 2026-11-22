package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.FilialenService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Filialen-Uebersicht des Ladens. Anlegen und Loeschen der Standorte laeuft
 * weiter ueber /api/shop/me/orte, die Zuordnung der Zugaenge ueber
 * /api/shop/team (ortId). Zugang wie ShopController ueber das JWT.
 */
@RestController
@RequestMapping("/api/shop/filialen")
public class FilialenController {

    private final FilialenService filialenService;
    private final com.example.stemplekarte.service.FilialStatistikService statistik;
    private final com.example.stemplekarte.service.FilialKorrekturService korrekturen;

    public FilialenController(FilialenService filialenService,
                              com.example.stemplekarte.service.FilialStatistikService statistik,
                              com.example.stemplekarte.service.FilialKorrekturService korrekturen) {
        this.filialenService = filialenService;
        this.statistik = statistik;
        this.korrekturen = korrekturen;
    }

    @Operation(summary = "Protokoll der Stempel-Korrekturen durch Filialen (die letzten 100)",
            description = "Wann, welche Filiale, welcher Login, von wie vielen auf wie viele Stempel. Keine Kundennamen.")
    @GetMapping("/korrekturen")
    public java.util.List<com.example.stemplekarte.service.FilialKorrekturService.Eintrag> korrekturen(
            Authentication auth) {
        Shop shop = ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
        return korrekturen.protokoll(shop);
    }

    @Operation(summary = "Statistik je Filiale im gewaehlten Zeitraum, plus Summe",
            description = "von/bis als JJJJ-MM-TT, beide Tage zaehlen mit; ohne Angabe die letzten 30 Tage. "
                    + "Je Filiale: scans, stempel, neueKunden (erster Stempel in dieser Filiale), "
                    + "praemien (eingeloest, erst ab Einfuehrung gezaehlt), aktiveKunden. Keine Kundendaten.")
    @GetMapping("/statistik")
    public com.example.stemplekarte.service.FilialStatistikService.Tabelle statistik(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String von,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String bis,
            Authentication auth) {
        Shop shop = ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
        return statistik.tabelle(shop, com.example.stemplekarte.service.FilialStatistikService.zeitraum(
                von, bis, com.example.stemplekarte.service.FilialStatistikService.heute()));
    }

    @Operation(summary = "Filialen mit Scans, Stempeln, Kunden der letzten 30 Tage und Zugaengen",
            description = "max = erlaubte Standorte (1 + Admin-Wert fuer weitere Orte). "
                    + "id HAUPT = Hauptstandort, sonst die Ort-ID aus /api/shop/me/orte.")
    @GetMapping
    public FilialenService.Stand stand(Authentication auth) {
        Shop shop = ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
        return filialenService.stand(shop, Instant.now());
    }
}
