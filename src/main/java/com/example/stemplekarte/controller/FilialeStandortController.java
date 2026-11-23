package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Das Unterkonto setzt den Standort der EIGENEN Filiale - per Knopf im
 * Laden, wie der Inhaber im Profil. Genauer als der Punkt aus der
 * Adresssuche. Die Filiale kommt immer aus dem Login, nie aus der Anfrage.
 *
 * Der Hauptstandort bleibt beim Hauptkonto (Profil): daran haengt die
 * Standort-Pflicht des ganzen Ladens.
 */
@RestController
@RequestMapping("/api/filiale/standort")
public class FilialeStandortController {

    private static final Logger log = LoggerFactory.getLogger(FilialeStandortController.class);
    static final String NUR_HAUPTKONTO = "Den Standort des Hauptstandorts legt das Hauptkonto im Profil fest.";

    private final ShopOrtRepository orte;

    public FilialeStandortController(ShopOrtRepository orte) {
        this.orte = orte;
    }

    public record StandortRequest(Double latitude, Double longitude) {}

    private ShopOrt eigene(JwtAuthFilter.FilialPrincipal p) {
        String ortId = p.zugang().getOrtId();
        if (ortId == null) throw new IllegalArgumentException(NUR_HAUPTKONTO);
        return orte.findByIdAndShop(ortId, p.shop())
                .orElseThrow(() -> new NoSuchElementException("Filiale nicht gefunden"));
    }

    private static Map<String, Object> antwort(ShopOrt o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", o.getLabel());
        m.put("adresse", o.getAdresse());
        m.put("latitude", o.getLatitude());
        m.put("longitude", o.getLongitude());
        return m;
    }

    @Operation(summary = "Standort der eigenen Filiale")
    @GetMapping
    public Map<String, Object> stand(Authentication auth) {
        return antwort(eigene(FilialeController.filiale(auth)));
    }

    @Operation(summary = "Standort der eigenen Filiale setzen",
            description = "Koordinaten aus dem Browser des Geraets, das gerade in der Filiale steht.")
    @PutMapping
    @Transactional
    public Map<String, Object> setzen(@RequestBody StandortRequest req, Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = FilialeController.filiale(auth);
        ShopOrt ort = eigene(p);
        if (req.latitude() == null || req.longitude() == null
                || req.latitude() < -90 || req.latitude() > 90
                || req.longitude() < -180 || req.longitude() > 180
                || (req.latitude() == 0 && req.longitude() == 0)) {
            throw new IllegalArgumentException("Der Standort ist ungültig.");
        }
        ort.verlegen(req.latitude(), req.longitude());
        orte.save(ort);
        log.info("[FILIALE] Standort gesetzt laden={} filiale={} von={}",
                p.shop().getId(), ort.getId(), p.zugang().getBenutzername());
        return antwort(ort);
    }
}
