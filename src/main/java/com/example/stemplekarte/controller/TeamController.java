package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.TeamService;
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
import java.util.List;
import java.util.Map;

/**
 * Mitarbeiter-Zugaenge des Ladens. Zugang ueber das JWT des Inhabers
 * (/api/shop/** verlangt ROLE_SHOP). Die aelteren Endpunkte /staff-token
 * und /staff-tokens bleiben fuer Scanner-Einrichtung und Login bestehen.
 */
@RestController
@RequestMapping("/api/shop/team")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    /** ortId: HAUPT oder leer = Hauptstandort, sonst eine Ort-ID aus /api/shop/me/orte; null = nicht aendern. */
    public record ZugangRequest(String label, String rolle, String ortId) {}

    @Operation(summary = "Mitarbeiter-Zugaenge",
            description = "Name, Rolle (MITARBEITER / AUSHILFE), Filiale (ortId, HAUPT = Hauptstandort), "
                    + "zuletzt benutzt, Stempel der letzten 7 Tage.")
    @GetMapping
    public List<TeamService.Zugang> liste(Authentication auth) {
        return teamService.liste(currentShop(auth), Instant.now());
    }

    @PostMapping
    public TeamService.Zugang anlegen(@RequestBody ZugangRequest req, Authentication auth) {
        Shop shop = currentShop(auth);
        var t = teamService.anlegen(shop, req.label(), req.rolle(), req.ortId());
        return teamService.zugang(t, shop.getId(), Instant.now());
    }

    @PutMapping("/{token}")
    public TeamService.Zugang aendern(@PathVariable String token, @RequestBody ZugangRequest req,
                                      Authentication auth) {
        Shop shop = currentShop(auth);
        var t = teamService.aendern(shop, token, req.label(), req.rolle(), req.ortId());
        return teamService.zugang(t, shop.getId(), Instant.now());
    }

    @DeleteMapping("/{token}")
    public Map<String, Boolean> entfernen(@PathVariable String token, Authentication auth) {
        teamService.entfernen(currentShop(auth), token);
        return Map.of("ok", true);
    }
}
