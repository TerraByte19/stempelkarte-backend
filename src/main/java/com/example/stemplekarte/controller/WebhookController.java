package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Webhook des Ladens (Inhaber-Login). Nur mit Freischaltung durch den Admin. */
@RestController
@RequestMapping("/api/shop/webhook")
public class WebhookController {

    private final WebhookService dienst;

    public WebhookController(WebhookService dienst) {
        this.dienst = dienst;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    @Operation(summary = "Webhook-Stand", description = "URL, offene und fehlgeschlagene Zustellungen, die letzten 20. Nie das Geheimnis.")
    @GetMapping
    public WebhookService.Stand stand(Authentication auth) {
        return dienst.stand(currentShop(auth));
    }

    public record EinrichtenRequest(String url, Boolean neuesGeheimnis) {}

    @Operation(summary = "Webhook einrichten",
            description = "https-Adresse. Das Geheimnis fuer die Signatur steht nur in dieser Antwort "
                    + "(beim ersten Einrichten oder mit neuesGeheimnis=true).")
    @PutMapping
    public WebhookService.Stand einrichten(@RequestBody EinrichtenRequest req, Authentication auth) {
        return dienst.einrichten(currentShop(auth).getId(), req.url(), Boolean.TRUE.equals(req.neuesGeheimnis()));
    }

    @Operation(summary = "Webhook entfernen")
    @DeleteMapping
    public WebhookService.Stand entfernen(Authentication auth) {
        return dienst.entfernen(currentShop(auth).getId());
    }

    @Operation(summary = "Test-Ereignis schicken", description = "Landet in der Ausgangstabelle und geht binnen 20 Sekunden raus.")
    @PostMapping("/test")
    public WebhookService.Stand teste(Authentication auth) {
        return dienst.teste(currentShop(auth).getId());
    }
}
