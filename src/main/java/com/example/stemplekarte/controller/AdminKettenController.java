package com.example.stemplekarte.controller;

import com.example.stemplekarte.service.AdminKettenService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-Panel: Ketten. Ueberblick ueber Filialen und Logins eines Ladens und
 * die Eingriffe, wenn die Zentrale nicht weiterkommt. Nur ROLE_ADMIN
 * (SecurityConfig: /api/admin/**). Jeder Eingriff wird protokolliert.
 */
// KEIN @CrossOrigin hier — CORS läuft global über SecurityConfig
@Tag(name = "Admin", description = "Admin-Endpoints")
@RestController
@RequestMapping("/api/admin/shops/{shopId}/kette")
public class AdminKettenController {

    private final AdminKettenService dienst;

    public AdminKettenController(AdminKettenService dienst) {
        this.dienst = dienst;
    }

    public record FilialeRequest(String label, Double latitude, Double longitude) {}
    public record AktivRequest(boolean aktiv) {}
    public record LoginRequest(String benutzername, String passwort, String ortId) {}
    public record PasswortRequest(String passwort) {}
    public record SperreRequest(boolean gesperrt) {}
    public record GeraeteRequest(int anzahl) {}

    @GetMapping
    public AdminKettenService.Uebersicht uebersicht(@PathVariable String shopId) {
        return dienst.uebersicht(shopId);
    }

    @PostMapping("/filialen")
    public AdminKettenService.Uebersicht filialeAnlegen(
            @PathVariable String shopId, @RequestBody FilialeRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        return dienst.filialeAnlegen(shopId, req.label(), req.latitude(), req.longitude(), ip(forwardedFor));
    }

    @PostMapping("/filialen/{ortId}/aktiv")
    public AdminKettenService.Uebersicht filialeAktiv(
            @PathVariable String shopId, @PathVariable String ortId, @RequestBody AktivRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        return dienst.filialeAktiv(shopId, ortId, req.aktiv(), ip(forwardedFor));
    }

    @PostMapping("/logins")
    public AdminKettenService.Uebersicht loginAnlegen(
            @PathVariable String shopId, @RequestBody LoginRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        return dienst.loginAnlegen(shopId, req.benutzername(), req.passwort(), req.ortId(), ip(forwardedFor));
    }

    @PostMapping("/filialen/{ortId}/bestaetigen")
    public AdminKettenService.Uebersicht filialeBestaetigen(
            @PathVariable String shopId, @PathVariable String ortId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        return dienst.filialeBestaetigen(shopId, ortId, ip(forwardedFor));
    }

    @PostMapping("/filialen/{ortId}/ablehnen")
    public AdminKettenService.Uebersicht filialeAblehnen(
            @PathVariable String shopId, @PathVariable String ortId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        return dienst.filialeAblehnen(shopId, ortId, ip(forwardedFor));
    }

    @PostMapping("/logins/{zugangId}/passwort")
    public AdminKettenService.Uebersicht loginPasswort(
            @PathVariable String shopId, @PathVariable String zugangId, @RequestBody PasswortRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        return dienst.loginPasswort(shopId, zugangId, req.passwort(), ip(forwardedFor));
    }

    @PostMapping("/logins/{zugangId}/sperre")
    public AdminKettenService.Uebersicht loginSperre(
            @PathVariable String shopId, @PathVariable String zugangId, @RequestBody SperreRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        return dienst.loginSperre(shopId, zugangId, req.gesperrt(), ip(forwardedFor));
    }

    @PostMapping("/geraete-je-filiale")
    public AdminKettenService.Uebersicht geraeteJeFiliale(
            @PathVariable String shopId, @RequestBody GeraeteRequest req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        return dienst.geraeteJeFiliale(shopId, req.anzahl(), ip(forwardedFor));
    }

    /** Erste Adresse aus X-Forwarded-For (hinter Render steht dort die echte IP). */
    private static String ip(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) return null;
        int komma = forwardedFor.indexOf(',');
        return (komma > 0 ? forwardedFor.substring(0, komma) : forwardedFor).trim();
    }
}
