package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.FilialZugangService;
import com.example.stemplekarte.service.FilialenService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Das Hauptkonto einer Kette verwaltet die Logins seiner Filialen. Zugang
 * wie ShopController ueber das JWT des Inhabers. Passwort und Hash stehen in
 * keiner Antwort.
 */
@RestController
@RequestMapping("/api/shop/filial-zugaenge")
public class FilialZugangController {

    private final FilialZugangService service;

    public FilialZugangController(FilialZugangService service) {
        this.service = service;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    public record NeuRequest(String benutzername, String passwort, String ortId) {}
    public record PasswortRequest(String passwort) {}
    public record SperreRequest(boolean gesperrt) {}

    public record Eintrag(String id, String benutzername, String ortId, String ortLabel,
                          boolean gesperrt, Instant erstellt, Instant zuletztEingeloggt) {}

    private Eintrag eintrag(FilialZugang z) {
        return new Eintrag(z.getId(), z.getBenutzername(),
                z.getOrtId() != null ? z.getOrtId() : FilialenService.HAUPT,
                service.ortLabel(z), z.isGesperrt(), z.getCreatedAt(), z.getLastLoginAt());
    }

    @Operation(summary = "Logins der Filialen (nur Tarif Kette)")
    @GetMapping
    public List<Eintrag> liste(Authentication auth) {
        return service.liste(currentShop(auth)).stream().map(this::eintrag).toList();
    }

    @Operation(summary = "Login für eine Filiale anlegen",
            description = "ortId = ID aus /api/shop/me/orte oder HAUPT. Passwort mindestens 8 Zeichen.")
    @PostMapping
    public Eintrag anlegen(Authentication auth, @RequestBody NeuRequest req) {
        return eintrag(service.anlegen(currentShop(auth), req.benutzername(), req.passwort(), req.ortId()));
    }

    @Operation(summary = "Neues Passwort setzen - alle bisherigen Anmeldungen dieses Logins enden sofort")
    @PutMapping("/{id}/passwort")
    public Eintrag passwort(Authentication auth, @PathVariable String id, @RequestBody PasswortRequest req) {
        return eintrag(service.passwortSetzen(currentShop(auth), id, req.passwort()));
    }

    @Operation(summary = "Login sperren oder wieder freigeben")
    @PutMapping("/{id}/sperre")
    public Eintrag sperre(Authentication auth, @PathVariable String id, @RequestBody SperreRequest req) {
        return eintrag(service.sperren(currentShop(auth), id, req.gesperrt()));
    }

    @Operation(summary = "Login löschen")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> loeschen(Authentication auth, @PathVariable String id) {
        service.loeschen(currentShop(auth), id);
        return ResponseEntity.noContent().build();
    }
}
