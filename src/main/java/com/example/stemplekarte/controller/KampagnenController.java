package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.KampagnenService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Push-Kampagnen des Ladens. Eigener Controller, damit der ShopController
 * nicht weiter waechst; Zugang wie dort ueber das JWT (/api/shop/** verlangt
 * ROLE_SHOP in der SecurityConfig).
 */
@RestController
@RequestMapping("/api/shop/kampagnen")
public class KampagnenController {

    private final KampagnenService kampagnenService;
    private final KampagneRepository kampagneRepo;

    public KampagnenController(KampagnenService kampagnenService, KampagneRepository kampagneRepo) {
        this.kampagnenService = kampagnenService;
        this.kampagneRepo = kampagneRepo;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    // gruppe: Zielgruppe.name(), leer = ALLE. geplantAm: ISO-Zeitpunkt, leer = sofort.
    // kontrollgruppe: ein Zehntel bekommt nichts, fuer den Vergleich.
    public record KampagneRequest(String text, String gruppe, String geplantAm, Boolean kontrollgruppe) {}

    // wiedergekommen: Empfaenger, die in den 7 Tagen danach gescannt haben.
    // Null solange die Kampagne nicht fertig ist; ergebnisFest = Fenster um.
    // kontrolleEmpfaenger/-Wiedergekommen: dasselbe fuer die Kontrollgruppe
    // (0 bzw. null, wenn es keine gab).
    public record KampagneItem(String id, String text, String gruppe, String status, int empfaenger,
                               int gesendet, String erstelltAm, String geplantAm, String fertigAm,
                               Integer wiedergekommen, boolean ergebnisFest,
                               int kontrolleEmpfaenger, Integer kontrolleWiedergekommen) {
        static KampagneItem von(Kampagne k, KampagnenService.Ergebnis ergebnis) {
            return new KampagneItem(k.getId(), k.getText(), k.getGruppe(), k.getStatus(),
                    k.getEmpfaenger(), k.getGesendet(), k.getErstelltAm().toString(),
                    k.getGeplantAm() != null ? k.getGeplantAm().toString() : null,
                    k.getFertigAm() != null ? k.getFertigAm().toString() : null,
                    ergebnis != null ? ergebnis.mitNachricht() : null, k.ergebnisFest(),
                    Math.max(0, k.getKontrolleEmpfaenger()),
                    ergebnis != null ? ergebnis.kontrolle() : null);
        }
    }

    public record GruppeItem(String gruppe, long empfaenger, long heuteSchon) {}

    public record KampagnenStand(int textMax, List<GruppeItem> gruppen, List<KampagneItem> items) {}

    @Operation(summary = "Stand der Push-Kampagnen",
            description = "Je Zielgruppe die Empfaengerzahl (ohne die, die heute schon eine "
                    + "bekommen haben), und die letzten 20 Kampagnen.")
    @GetMapping
    public KampagnenStand stand(Authentication auth) {
        Shop shop = currentShop(auth);
        List<GruppeItem> gruppen = kampagnenService.zaehle(shop).stream()
                .map(z -> new GruppeItem(z.gruppe().name(), z.empfaenger(), z.heuteSchon()))
                .toList();
        Instant jetzt = Instant.now();
        List<KampagneItem> items = kampagneRepo.findTop20ByShopOrderByErstelltAmDesc(shop)
                .stream().map(k -> KampagneItem.von(k, ergebnis(k, shop, jetzt))).toList();
        return new KampagnenStand(Kampagne.TEXT_MAX, gruppen, items);
    }

    // Fest = gespeicherter Wert. Fertig, aber Fenster offen = live zaehlen.
    // Geplant oder laufend = noch kein Ergebnis.
    private KampagnenService.Ergebnis ergebnis(Kampagne k, Shop shop, Instant jetzt) {
        if (k.ergebnisFest()) {
            return new KampagnenService.Ergebnis(k.getWiedergekommen() != null ? k.getWiedergekommen() : 0,
                    k.getKontrolleWiedergekommen() != null ? k.getKontrolleWiedergekommen() : 0);
        }
        if (!Kampagne.FERTIG.equals(k.getStatus())) return null;
        return kampagnenService.wiedergekommen(k, shop.getId(), jetzt);
    }

    @Operation(summary = "Push-Kampagne starten oder planen",
            description = "Text 1-160 Zeichen an eine Zielgruppe. Jede Karte bekommt hoechstens "
                    + "eine Kampagne am Tag. geplantAm (ISO) = spaeter senden, die Empfaenger "
                    + "werden dann zum Zeitpunkt bestimmt. Der Versand laeuft im Hintergrund.")
    @PostMapping
    public KampagneItem starte(@RequestBody KampagneRequest req, Authentication auth) {
        Instant geplant = null;
        if (req.geplantAm() != null && !req.geplantAm().isBlank()) {
            try {
                geplant = Instant.parse(req.geplantAm().trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Ungültiger Zeitpunkt");
            }
        }
        return KampagneItem.von(kampagnenService.starte(currentShop(auth), req.text(), req.gruppe(), geplant,
                Boolean.TRUE.equals(req.kontrollgruppe())), null);
    }

    @Operation(summary = "Geplante Kampagne abbrechen")
    @DeleteMapping("/{id}")
    public Map<String, Object> abbrechen(@PathVariable String id, Authentication auth) {
        kampagnenService.abbrechen(currentShop(auth), id);
        return Map.of("ok", true);
    }
}
