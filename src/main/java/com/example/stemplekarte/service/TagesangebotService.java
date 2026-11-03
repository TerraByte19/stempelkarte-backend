package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.NoSuchElementException;

/**
 * Tagesangebot: ein Satz, der bis Tagesende auf der Karte steht und beim
 * Setzen als Kampagne ("Heute: ...") aufs Handy geht. Die Kampagne laeuft
 * ueber den normalen Weg - Tageslimit pro Person, iPhone-Auffrischen,
 * Verlauf. Hat heute schon jeder eine Nachricht bekommen, bleibt das
 * Angebot trotzdem gespeichert; nur der Push faellt aus.
 */
@Service
public class TagesangebotService {

    private static final Logger log = LoggerFactory.getLogger(TagesangebotService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final String PRAEFIX = "Heute: ";

    private final ShopRepository shopRepo;
    private final KampagnenService kampagnenService;

    public TagesangebotService(ShopRepository shopRepo, KampagnenService kampagnenService) {
        this.shopRepo = shopRepo;
        this.kampagnenService = kampagnenService;
    }

    /** aktiv = gilt gerade; text bleibt nach Ablauf als Vorschlag stehen. empfaenger nur nach dem Setzen. */
    public record Stand(String text, String bis, boolean aktiv, int empfaenger) {}

    public Stand stand(Shop shop) {
        return stand(shop, Instant.now(), 0);
    }

    private static Stand stand(Shop shop, Instant jetzt, int empfaenger) {
        return new Stand(shop.getTagesangebotText(),
                shop.getTagesangebotBis() != null ? shop.getTagesangebotBis().toString() : null,
                shop.tagesangebot(jetzt) != null, empfaenger);
    }

    public Stand setzen(String shopId, String roh) {
        return setzen(shopId, roh, Instant.now());
    }

    /**
     * Speichern und pushen. Bewusst KEINE Transaktion um beides: das
     * Speichern ist mit save() durch, bevor die Kampagne startet. Schlaegt
     * der Push fehl (niemand uebrig fuer heute, Datenbankfehler), bleibt das
     * Angebot trotzdem - und eine in starte() geworfene und hier gefangene
     * Ausnahme kann keine gemeinsame Transaktion auf rollback-only setzen.
     */
    Stand setzen(String shopId, String roh, Instant jetzt) {
        String text = KampagnenService.sauber(roh);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Das Angebot darf nicht leer sein");
        }
        if (text.length() > Shop.TAGESANGEBOT_MAX) {
            throw new IllegalArgumentException("Das Angebot darf höchstens " + Shop.TAGESANGEBOT_MAX + " Zeichen haben");
        }
        Instant bis = jetzt.atZone(ZONE).toLocalDate().plusDays(1).atStartOfDay(ZONE).toInstant();
        Shop shop = speichern(shopId, text, jetzt, bis);
        int empfaenger = 0;
        try {
            Kampagne k = kampagnenService.starte(shop, PRAEFIX + text, Zielgruppe.TAGESANGEBOT.name());
            empfaenger = k.getEmpfaenger();
            log.info("[TAGESANGEBOT] Laden {}: Kampagne {} an {} Karten", shopId, k.getId(), empfaenger);
        } catch (IllegalArgumentException e) {
            // Alle hatten heute schon eine Nachricht (zweite Aenderung am
            // selben Tag) oder der Laden hat noch keine Kunden.
            log.info("[TAGESANGEBOT] Laden {}: gespeichert ohne Push ({})", shopId, e.getMessage());
        }
        return stand(shop, jetzt, empfaenger);
    }

    /** save() bringt seine eigene Transaktion mit; der Rueckgabewert ist die gespeicherte Fassung. */
    Shop speichern(String shopId, String text, Instant jetzt, Instant bis) {
        Shop shop = laden(shopId);
        shop.setzeTagesangebot(text, jetzt, bis);
        return shopRepo.save(shop);
    }

    @Transactional
    public Stand loeschen(String shopId) {
        Shop shop = laden(shopId);
        Instant jetzt = Instant.now();
        shop.loescheTagesangebot(jetzt);
        shopRepo.save(shop);
        log.info("[TAGESANGEBOT] Laden {}: entfernt", shopId);
        return stand(shop, jetzt, 0);
    }

    private Shop laden(String shopId) {
        return shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
    }
}
