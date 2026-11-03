package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.TagesangebotService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tagesangebot des Ladens. Zugang ueber das JWT wie ShopController. */
@RestController
@RequestMapping("/api/shop/tagesangebot")
public class TagesangebotController {

    private final TagesangebotService dienst;

    public TagesangebotController(TagesangebotService dienst) {
        this.dienst = dienst;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    @Operation(summary = "Tagesangebot", description = "text, bis (ISO), aktiv = gilt gerade.")
    @GetMapping
    public TagesangebotService.Stand stand(Authentication auth) {
        return dienst.stand(currentShop(auth));
    }

    public record SetzenRequest(String text) {}

    @Operation(summary = "Tagesangebot setzen",
            description = "Hoechstens 80 Zeichen, gilt bis Tagesende. Geht als Kampagne 'Heute: ...' an alle Karten; "
                    + "empfaenger = wie viele Karten den Push bekommen (0, wenn heute schon alle eine Nachricht hatten).")
    @PutMapping
    public TagesangebotService.Stand setzen(@RequestBody SetzenRequest req, Authentication auth) {
        return dienst.setzen(currentShop(auth).getId(), req.text());
    }

    @Operation(summary = "Tagesangebot entfernen", description = "Kein Push.")
    @DeleteMapping
    public TagesangebotService.Stand loeschen(Authentication auth) {
        return dienst.loeschen(currentShop(auth).getId());
    }
}
