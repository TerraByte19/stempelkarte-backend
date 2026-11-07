package com.example.stemplekarte.controller;

import com.example.stemplekarte.service.BewertungsLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/** Gratis-Tool auf der Website: Laden suchen, Bewertungslink und QR-Code bekommen. Offen, gedrosselt. */
@Tag(name = "Bewertungslink", description = "Gratis-Tool: Google-Bewertungslink und QR-Code fuer Laeden")
@RestController
@RequestMapping("/api/public/bewertungslink")
public class BewertungsLinkController {

    private final BewertungsLinkService dienst;

    public BewertungsLinkController(BewertungsLinkService dienst) {
        this.dienst = dienst;
    }

    @Operation(summary = "Laden bei Google suchen",
            description = "q = Name und Ort. aktiv=false, wenn kein Google-Schluessel eingetragen ist; dann nur Place-ID von Hand.")
    @GetMapping
    public BewertungsLinkService.Ergebnis suche(@RequestParam(required = false) String q,
                                                @RequestHeader(value = "X-Forwarded-For", required = false) String forwarded) {
        return dienst.suche(q, clientKey(forwarded), Instant.now());
    }

    @Operation(summary = "Link fuer eine Place-ID", description = "Fuer den Weg ohne Suche: Place-ID eingeben, Link zurueck.")
    @GetMapping("/link")
    public java.util.Map<String, String> link(@RequestParam String placeId) {
        return java.util.Map.of("link", BewertungsLinkService.linkFuer(placeId));
    }

    @Operation(summary = "QR-Code des Bewertungslinks", description = "PNG 600 px, einen Tag cachebar.")
    @GetMapping(value = "/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@RequestParam String placeId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .header("Content-Disposition", "inline; filename=\"google-bewertung-qr.png\"")
                .body(BewertungsLinkService.qrPng(placeId));
    }

    private static String clientKey(String forwarded) {
        if (forwarded == null || forwarded.isBlank()) return "unbekannt";
        return forwarded.split(",")[0].trim();
    }
}
