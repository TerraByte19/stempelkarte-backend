package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.ApiKeyService;
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
import java.util.Map;

/** API-Schluessel des Ladens (Inhaber-Login). Nur mit Freischaltung durch den Admin. */
@RestController
@RequestMapping("/api/shop/api-keys")
public class ApiKeyController {

    private final ApiKeyService dienst;

    public ApiKeyController(ApiKeyService dienst) {
        this.dienst = dienst;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    @Operation(summary = "API-Schluessel des Ladens",
            description = "freigeschaltet = der Admin hat die Schnittstelle fuer den Laden geoeffnet. "
                    + "Die Liste zeigt nur die ersten Zeichen, nie den Schluessel.")
    @GetMapping
    public Map<String, Object> liste(Authentication auth) {
        Shop shop = currentShop(auth);
        List<ApiKeyService.Eintrag> keys = shop.isApiFreigeschaltet() ? dienst.liste(shop) : List.of();
        return Map.of("freigeschaltet", shop.isApiFreigeschaltet(), "keys", keys, "max", ApiKeyService.MAX_JE_LADEN);
    }

    public record AnlegenRequest(String label) {}

    @Operation(summary = "API-Schluessel anlegen",
            description = "Der Schluessel steht nur in dieser Antwort - danach nie wieder.")
    @PostMapping
    public ApiKeyService.Neu anlegen(@RequestBody AnlegenRequest req, Authentication auth) {
        return dienst.anlegen(currentShop(auth).getId(), req.label());
    }

    @Operation(summary = "API-Schluessel widerrufen")
    @DeleteMapping("/{id}")
    public Map<String, Object> widerrufen(@PathVariable String id, Authentication auth) {
        Shop shop = currentShop(auth);
        dienst.widerrufen(shop, id);
        return Map.of("freigeschaltet", shop.isApiFreigeschaltet(), "keys", dienst.liste(shop), "max", ApiKeyService.MAX_JE_LADEN);
    }
}
