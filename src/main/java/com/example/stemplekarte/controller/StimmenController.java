package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.StimmenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Stimmen von Laeden fuer die Startseite - drei Zugaenge in einer Klasse:
 * der Laden (JWT, /api/shop/stimme), der Admin (/api/admin/stimmen) und die
 * Website (/api/public/stimmen, frei). Wer was darf, regelt die
 * SecurityConfig ueber die Pfade.
 */
@Tag(name = "Stimmen", description = "Zitate von Laeden fuer die Startseite")
@RestController
public class StimmenController {

    private final StimmenService dienst;

    public StimmenController(StimmenService dienst) {
        this.dienst = dienst;
    }

    private static Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    // ---- Laden ----

    @Operation(summary = "Meine Stimme", description = "status null = noch nichts eingereicht; sonst OFFEN, FREIGEGEBEN oder ABGELEHNT.")
    @GetMapping("/api/shop/stimme")
    public StimmenService.Stand meine(Authentication auth) {
        return dienst.meine(currentShop(auth));
    }

    @Operation(summary = "Stimme einreichen oder aendern",
            description = "zitat 10 bis 320 Zeichen, ort Pflicht, person optional, einverstanden muss true sein. "
                    + "Nach jeder Aenderung wartet die Stimme wieder auf Freigabe.")
    @PutMapping("/api/shop/stimme")
    public StimmenService.Stand einreichen(@RequestBody StimmenService.Eingabe eingabe, Authentication auth) {
        return dienst.einreichen(currentShop(auth), eingabe, Instant.now());
    }

    @Operation(summary = "Stimme zurueckziehen", description = "Verschwindet sofort, auch von der Website.")
    @DeleteMapping("/api/shop/stimme")
    public ResponseEntity<Void> zurueckziehen(Authentication auth) {
        dienst.zurueckziehen(currentShop(auth));
        return ResponseEntity.noContent().build();
    }

    // ---- Admin ----

    @Operation(summary = "Alle Stimmen", description = "Neueste Aenderung zuerst, mit Laden und Einverstaendnis-Zeitpunkt.")
    @GetMapping("/api/admin/stimmen")
    public List<StimmenService.AdminZeile> alle() {
        return dienst.alle();
    }

    @PostMapping("/api/admin/stimmen/{id}/freigeben")
    public StimmenService.AdminZeile freigeben(@PathVariable String id) {
        return dienst.freigeben(id, Instant.now());
    }

    @PostMapping("/api/admin/stimmen/{id}/ablehnen")
    public StimmenService.AdminZeile ablehnen(@PathVariable String id) {
        return dienst.ablehnen(id, Instant.now());
    }

    // ---- Website ----

    @Operation(summary = "Freigegebene Stimmen", description = "Hoechstens sechs, zuletzt freigegebene zuerst. 5 Minuten cachebar.")
    @GetMapping("/api/public/stimmen")
    public ResponseEntity<List<StimmenService.Oeffentlich>> oeffentlich() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(dienst.oeffentlich());
    }
}
