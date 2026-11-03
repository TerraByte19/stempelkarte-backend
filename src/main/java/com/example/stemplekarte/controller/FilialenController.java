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

    public FilialenController(FilialenService filialenService) {
        this.filialenService = filialenService;
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
