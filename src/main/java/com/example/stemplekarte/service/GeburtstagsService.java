package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatischer Geburtstagsgruss: ab 9 Uhr bekommt jede Karte, die heute
 * Geburtstag hat, eine Nachricht - als ganz normale Kampagne mit der
 * internen Zielgruppe GEBURTSTAG_HEUTE. Damit laufen Versand, iPhone-
 * Auffrischen, Tageslimit pro Person und Verlauf ueber denselben Weg wie
 * beim Laden selbst.
 *
 * Kein Cron um Punkt 9: schlaeft die Instanz da gerade (Render), waere der
 * Tag verloren. Stattdessen alle 15 Minuten nachsehen, ob es 9 Uhr war und
 * der Laden heute noch dran ist. "Schon dran" steht in der Datenbank (eine
 * Kampagne dieser Gruppe von heute), der Merker im Speicher spart nur die
 * Abfrage bei Laeden ohne Geburtstagskind.
 */
@Service
public class GeburtstagsService {

    private static final Logger log = LoggerFactory.getLogger(GeburtstagsService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final int AB_STUNDE = 9;

    private final ShopRepository shopRepo;
    private final KampagneRepository kampagneRepo;
    private final KampagnenService kampagnenService;

    // Laden-ID -> Tag, an dem zuletzt geprueft wurde.
    private final Map<String, LocalDate> geprueft = new ConcurrentHashMap<>();

    public GeburtstagsService(ShopRepository shopRepo, KampagneRepository kampagneRepo,
                              KampagnenService kampagnenService) {
        this.shopRepo = shopRepo;
        this.kampagneRepo = kampagneRepo;
        this.kampagnenService = kampagnenService;
    }

    @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 2 * 60_000)
    public void lauf() {
        try {
            lauf(Instant.now());
        } catch (Exception e) {
            log.warn("[GEBURTSTAG] Lauf abgebrochen: {}", e.getMessage());
        }
    }

    /** Paketsichtbar mit Zeitpunkt, damit der Test die Uhr stellen kann. */
    int lauf(Instant jetzt) {
        var zdt = jetzt.atZone(ZONE);
        if (zdt.getHour() < AB_STUNDE) return 0;
        LocalDate heute = zdt.toLocalDate();
        Instant tagesbeginn = heute.atStartOfDay(ZONE).toInstant();
        int gestartet = 0;
        for (Shop shop : shopRepo.findAll()) {
            if (!shop.isActive() || !shop.isGeburtstagsgrussAktiv()) continue;
            // Geburtstagsgruss zaehlt zu den automatischen Nachrichten (ab Pro).
            if (!TarifPruefung.darf(shop, com.example.stemplekarte.model.Tarif.Funktion.AUTO_NACHRICHTEN)) continue;
            if (heute.equals(geprueft.get(shop.getId()))) continue;
            try {
                if (kampagneRepo.existsByShopAndGruppeAndErstelltAmGreaterThanEqual(
                        shop, Zielgruppe.GEBURTSTAG_HEUTE.name(), tagesbeginn)) {
                    geprueft.put(shop.getId(), heute);
                    continue;
                }
                Kampagne k = kampagnenService.starte(shop, shop.geburtstagsText(), Zielgruppe.GEBURTSTAG_HEUTE.name());
                gestartet++;
                log.info("[GEBURTSTAG] Laden {}: Kampagne {} an {} Karten", shop.getId(), k.getId(), k.getEmpfaenger());
            } catch (IllegalArgumentException e) {
                // Niemand hat heute Geburtstag (oder alle hatten heute schon
                // eine Nachricht). Morgen wieder.
                log.debug("[GEBURTSTAG] Laden {}: {}", shop.getId(), e.getMessage());
            } catch (Exception e) {
                // Ein kaputter Laden haelt die anderen nicht auf.
                log.warn("[GEBURTSTAG] Laden {} fehlgeschlagen: {}", shop.getId(), e.getMessage());
                continue;
            }
            geprueft.put(shop.getId(), heute);
        }
        return gestartet;
    }
}
