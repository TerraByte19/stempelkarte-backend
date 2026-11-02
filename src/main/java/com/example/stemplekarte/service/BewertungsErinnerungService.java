package com.example.stemplekarte.service;

import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.KampagneEmpfaenger;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.KampagneEmpfaengerRepository;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bewertungs-Erinnerung: wer gestern da war und genug Besuche hat, bekommt
 * am Vormittag einmalig "Magst du uns bei Google bewerten?" mit dem Link -
 * als ganz normale Kampagne mit der internen Zielgruppe BEWERTUNG. Versand,
 * iPhone-Auffrischen, Tageslimit pro Person und Verlauf laufen damit ueber
 * denselben Weg wie beim Geburtstagsgruss.
 *
 * Einmal pro Laden und Tag, ab 10 Uhr: ein Block statt alle 15 Minuten
 * eine Mini-Kampagne, und der Kunde hat den Besuch noch im Kopf.
 */
@Service
public class BewertungsErinnerungService {

    private static final Logger log = LoggerFactory.getLogger(BewertungsErinnerungService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final int AB_STUNDE = 10;

    private final ShopRepository shopRepo;
    private final KampagneRepository kampagneRepo;
    private final KampagneEmpfaengerRepository empfaengerRepo;
    private final CustomerCardRepository customerCardRepo;
    private final KampagnenService kampagnenService;
    private final TransactionTemplate tx;

    // Laden-ID -> Tag, an dem zuletzt geprueft wurde.
    private final Map<String, LocalDate> geprueft = new ConcurrentHashMap<>();

    public BewertungsErinnerungService(ShopRepository shopRepo, KampagneRepository kampagneRepo,
                                       KampagneEmpfaengerRepository empfaengerRepo,
                                       CustomerCardRepository customerCardRepo,
                                       KampagnenService kampagnenService, TransactionTemplate tx) {
        this.shopRepo = shopRepo;
        this.kampagneRepo = kampagneRepo;
        this.empfaengerRepo = empfaengerRepo;
        this.customerCardRepo = customerCardRepo;
        this.kampagnenService = kampagnenService;
        this.tx = tx;
    }

    @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 3 * 60_000)
    public void lauf() {
        try {
            lauf(Instant.now());
        } catch (Exception e) {
            log.warn("[BEWERTUNG] Lauf abgebrochen: {}", e.getMessage());
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
            if (!shop.isActive() || shop.getBewertungErinnerungAb() <= 0) continue;
            if (shop.getGoogleReviewUrl() == null || shop.getGoogleReviewUrl().isBlank()) continue;
            if (heute.equals(geprueft.get(shop.getId()))) continue;
            try {
                if (kampagneRepo.existsByShopAndGruppeAndErstelltAmGreaterThanEqual(
                        shop, Zielgruppe.BEWERTUNG.name(), tagesbeginn)) {
                    geprueft.put(shop.getId(), heute);
                    continue;
                }
                String text = text(shop);
                if (text == null) {
                    log.warn("[BEWERTUNG] Laden {}: Google-Link zu lang fuer eine Nachricht", shop.getId());
                    geprueft.put(shop.getId(), heute);
                    continue;
                }
                Kampagne k = kampagnenService.starte(shop, text, Zielgruppe.BEWERTUNG.name());
                markiere(k, shop.getId(), jetzt);
                gestartet++;
                log.info("[BEWERTUNG] Laden {}: Kampagne {} an {} Karten", shop.getId(), k.getId(), k.getEmpfaenger());
            } catch (IllegalArgumentException e) {
                // Niemand faellig (oder alle hatten heute schon eine Nachricht). Morgen wieder.
                log.debug("[BEWERTUNG] Laden {}: {}", shop.getId(), e.getMessage());
            } catch (Exception e) {
                // Ein kaputter Laden haelt die anderen nicht auf.
                log.warn("[BEWERTUNG] Laden {} fehlgeschlagen: {}", shop.getId(), e.getMessage());
                continue;
            }
            geprueft.put(shop.getId(), heute);
        }
        return gestartet;
    }

    /**
     * Der Text mit Link. Passt die lange Form nicht in eine Nachricht
     * (Kampagne.TEXT_MAX), die kurze; passt auch die nicht: null.
     */
    static String text(Shop shop) {
        String url = shop.getGoogleReviewUrl();
        String lang = "Danke für deinen Besuch! Magst du uns bei Google bewerten? " + url;
        if (lang.length() <= Kampagne.TEXT_MAX) return lang;
        String kurz = "Magst du uns bei Google bewerten? " + url;
        return kurz.length() <= Kampagne.TEXT_MAX ? kurz : null;
    }

    /**
     * Einmal pro PERSON: die Empfaengerkarte und alle weiteren Karten
     * derselben Person bei diesem Laden bekommen den Merker. Sonst klingelt
     * es naechste Woche auf der Punktekarte noch einmal.
     */
    void markiere(Kampagne k, String shopId, Instant jetzt) {
        tx.executeWithoutResult(status -> {
            List<String> ids = empfaengerRepo.findByKampagneId(k.getId()).stream()
                    .map(KampagneEmpfaenger::getCustomerCardId).toList();
            for (CustomerCard cc : customerCardRepo.findAllById(ids)) {
                for (CustomerCard andere : customerCardRepo.findByCustomer(cc.getCustomer())) {
                    if (!shopId.equals(andere.getCard().getShop().getId())) continue;
                    if (andere.getBewertungErinnertAm() != null) continue;
                    andere.bewertungErinnert(jetzt);
                    customerCardRepo.save(andere);
                }
            }
        });
    }
}
