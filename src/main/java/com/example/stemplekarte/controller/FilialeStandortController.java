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
 * Ein Login am Hauptstandort setzt den Standort des Ladens selbst - denselben,
 * den das Hauptkonto im Profil setzt. Wer dort an der Kasse steht, ist oft
 * der Einzige, der wirklich im Laden ist.
 */
@RestController
@RequestMapping("/api/filiale/standort")
public class FilialeStandortController {

    private static final Logger log = LoggerFactory.getLogger(FilialeStandortController.class);
    private final ShopOrtRepository orte;
    private final com.example.stemplekarte.service.ShopService shopService;

    public FilialeStandortController(ShopOrtRepository orte,
                                     com.example.stemplekarte.service.ShopService shopService) {
        this.orte = orte;
        this.shopService = shopService;
    }

    public record StandortRequest(Double latitude, Double longitude) {}

    private ShopOrt eigene(JwtAuthFilter.FilialPrincipal p) {
        return orte.findByIdAndShop(p.zugang().getOrtId(), p.shop())
                .orElseThrow(() -> new NoSuchElementException("Filiale nicht gefunden"));
    }

    /** Hauptstandort: Koordinaten des Ladens, null solange keiner gesetzt ist. */
    private static Map<String, Object> antwortHaupt(com.example.stemplekarte.model.Shop shop) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", com.example.stemplekarte.service.FilialenService.HAUPT_LABEL);
        m.put("adresse", null);
        m.put("latitude", shop.getLatitude());
        m.put("longitude", shop.getLongitude());
        m.put("gesetzt", shop.getLatitude() != null && shop.getLongitude() != null);
        return m;
    }

    private static Map<String, Object> antwort(ShopOrt o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", o.getLabel());
        m.put("adresse", o.getAdresse());
        m.put("latitude", o.getLatitude());
        m.put("longitude", o.getLongitude());
        m.put("gesetzt", !o.isOhneStandort());
        return m;
    }

    @Operation(summary = "Standort der eigenen Filiale")
    @GetMapping
    public Map<String, Object> stand(Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = FilialeController.filiale(auth);
        if (p.zugang().getOrtId() == null) return antwortHaupt(shopService.getById(p.shop().getId()));
        return antwort(eigene(p));
    }

    @Operation(summary = "Standort der eigenen Filiale setzen",
            description = "Koordinaten aus dem Browser des Geraets, das gerade in der Filiale steht.")
    @PutMapping
    @Transactional
    public Map<String, Object> setzen(@RequestBody StandortRequest req, Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = FilialeController.filiale(auth);
        boolean haupt = p.zugang().getOrtId() == null;
        ShopOrt ort = haupt ? null : eigene(p);
        // NaN und Unendlich zuerst: sie bestehen jeden Groessenvergleich, und
        // ein solcher Punkt haette in der Geo-Pruefung die Entfernung 0 -
        // dann gaelte jedes Geraet ueberall als "im Laden".
        if (req.latitude() == null || req.longitude() == null
                || !Double.isFinite(req.latitude()) || !Double.isFinite(req.longitude())
                || req.latitude() < -90 || req.latitude() > 90
                || req.longitude() < -180 || req.longitude() > 180
                || (req.latitude() == 0 && req.longitude() == 0)) {
            throw new IllegalArgumentException("Der Standort ist ungültig.");
        }
        if (haupt) {
            // enabled = null: der Schalter der Sperrbildschirm-Erinnerung bleibt, wie er ist.
            com.example.stemplekarte.model.Shop neu = shopService.updateLockScreen(
                    p.shop().getId(), null, req.latitude(), req.longitude());
            log.info("[FILIALE] Standort gesetzt laden={} filiale=HAUPT von={}",
                    p.shop().getId(), p.zugang().getBenutzername());
            return antwortHaupt(neu);
        }
        ort.verlegen(req.latitude(), req.longitude());
        orte.save(ort);
        log.info("[FILIALE] Standort gesetzt laden={} filiale={} von={}",
                p.shop().getId(), ort.getId(), p.zugang().getBenutzername());
        return antwort(ort);
    }
}
