package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.AutomationAusloeser;
import com.example.stemplekarte.service.AutomationService;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Baukasten fuer eigene Automationen. Zugang wie ShopController ueber das
 * JWT (/api/shop/** verlangt ROLE_SHOP).
 */
@RestController
@RequestMapping("/api/shop/automationen")
public class AutomationController {

    private final AutomationService automationService;

    public AutomationController(AutomationService automationService) {
        this.automationService = automationService;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    public record Ausloeser(String key, String titel, String einheit, int min, int max) {}

    @Operation(summary = "Moegliche Ausloeser mit Titel und Wertebereich")
    @GetMapping("/ausloeser")
    public List<Ausloeser> ausloeser() {
        return Arrays.stream(AutomationAusloeser.values())
                .map(a -> new Ausloeser(a.name(), a.titel(), a.einheit(), a.min(), a.max()))
                .toList();
    }

    @Operation(summary = "Automationen des Ladens",
            description = "trifftJetzt = Personen, die beim naechsten Lauf (ab 10 Uhr) dran waeren.")
    @GetMapping
    public List<AutomationService.Eintrag> liste(Authentication auth) {
        return automationService.liste(currentShop(auth), Instant.now());
    }

    @Operation(summary = "Automation anlegen",
            description = "name, ausloeser, wert, text; optional cardId (nur diese Karte), gruppe "
                    + "(Kundengruppe), aktiv (Standard an). Hoechstens 10 je Laden.")
    @PostMapping
    public AutomationService.Eintrag anlegen(@RequestBody AutomationService.Anfrage req, Authentication auth) {
        return automationService.anlegen(currentShop(auth).getId(), req);
    }

    @Operation(summary = "Automation aendern oder pausieren", description = "Nur gesetzte Felder werden geaendert; aktiv=false pausiert.")
    @PutMapping("/{id}")
    public AutomationService.Eintrag aendern(@PathVariable String id, @RequestBody AutomationService.Anfrage req,
                                             Authentication auth) {
        return automationService.aendern(currentShop(auth).getId(), id, req);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> loeschen(@PathVariable String id, Authentication auth) {
        automationService.loeschen(currentShop(auth).getId(), id);
        return Map.of("ok", true);
    }
}
