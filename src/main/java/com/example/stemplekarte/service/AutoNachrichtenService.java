package com.example.stemplekarte.service;

import com.example.stemplekarte.model.AutoNachricht;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.KampagneEmpfaenger;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AutoNachrichtRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.KampagneEmpfaengerRepository;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatische Nachrichten: Willkommen, zweiter Besuch, fast voll, Praemie
 * bereit, VIP. Jeder eingeschaltete Automat startet ab 10 Uhr einmal am Tag
 * (VIP: einmal im Monat) eine ganz normale Kampagne mit seiner internen
 * Zielgruppe - Versand, iPhone-Auffrischen, Tageslimit pro Person und
 * Verlauf laufen wie beim Geburtstagsgruss. Danach bekommen die
 * Empfaengerkarten ihren Merker, damit dieselbe Nachricht nicht morgen
 * wieder kommt.
 *
 * Kein Cron um Punkt 10: schlaeft die Instanz da gerade (Render), waere der
 * Tag verloren. Alle 15 Minuten nachsehen, "schon dran" steht in der
 * Datenbank (Kampagne der Gruppe von heute), der Merker im Speicher spart
 * nur die Abfrage.
 */
@Service
public class AutoNachrichtenService {

    private static final Logger log = LoggerFactory.getLogger(AutoNachrichtenService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final int AB_STUNDE = 10;

    private final AutoNachrichtRepository autoRepo;
    private final ShopRepository shopRepo;
    private final KampagneRepository kampagneRepo;
    private final KampagneEmpfaengerRepository empfaengerRepo;
    private final CustomerCardRepository customerCardRepo;
    private final KampagnenService kampagnenService;
    private final TransactionTemplate tx;

    // "Laden|Typ" -> Tag (bzw. Monat als erster Tag), an dem zuletzt geprueft wurde.
    private final Map<String, LocalDate> geprueft = new ConcurrentHashMap<>();

    public AutoNachrichtenService(AutoNachrichtRepository autoRepo, ShopRepository shopRepo,
                                  KampagneRepository kampagneRepo, KampagneEmpfaengerRepository empfaengerRepo,
                                  CustomerCardRepository customerCardRepo, KampagnenService kampagnenService,
                                  TransactionTemplate tx) {
        this.autoRepo = autoRepo;
        this.shopRepo = shopRepo;
        this.kampagneRepo = kampagneRepo;
        this.empfaengerRepo = empfaengerRepo;
        this.customerCardRepo = customerCardRepo;
        this.kampagnenService = kampagnenService;
        this.tx = tx;
    }

    // ── Einstellungen ───────────────────────────────────────────────────

    /** Eine Zeile je Typ, auch wenn der Laden sie nie angefasst hat (dann aus, Standardtext). */
    public record Eintrag(String typ, boolean aktiv, String text, String standard) {}

    public List<Eintrag> liste(Shop shop) {
        Map<String, AutoNachricht> vorhanden = new java.util.HashMap<>();
        for (AutoNachricht a : autoRepo.findByShop(shop)) vorhanden.put(a.getTyp(), a);
        List<Eintrag> out = new ArrayList<>();
        for (AutoNachrichtTyp typ : AutoNachrichtTyp.values()) {
            AutoNachricht a = vorhanden.get(typ.name());
            out.add(new Eintrag(typ.name(), a != null && a.isAktiv(), a != null ? a.getText() : null, typ.standardText()));
        }
        return out;
    }

    /** Schalter und eigener Text (leer = Standard, hoechstens TEXT_MAX Zeichen). */
    @Transactional
    public Eintrag einstellen(String shopId, AutoNachrichtTyp typ, boolean aktiv, String text) {
        String sauber = KampagnenService.sauber(text);
        if (sauber.length() > AutoNachricht.TEXT_MAX) {
            throw new IllegalArgumentException("Der Text darf höchstens " + AutoNachricht.TEXT_MAX + " Zeichen haben");
        }
        Shop shop = shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
        Instant jetzt = Instant.now();
        AutoNachricht a = autoRepo.findByShopAndTyp(shop, typ.name())
                .orElseGet(() -> AutoNachricht.neu(shop, typ.name(), jetzt));
        a.einstellen(aktiv, sauber, jetzt);
        a = autoRepo.save(a);
        log.info("[AUTO] Laden {}: {} {}", shopId, typ, aktiv ? "an" : "aus");
        return new Eintrag(typ.name(), a.isAktiv(), a.getText(), typ.standardText());
    }

    // ── Laeufer ─────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 4 * 60_000)
    public void lauf() {
        try {
            lauf(Instant.now());
        } catch (Exception e) {
            log.warn("[AUTO] Lauf abgebrochen: {}", e.getMessage());
        }
    }

    /** Paketsichtbar mit Zeitpunkt, damit der Test die Uhr stellen kann. Gibt gestartete Kampagnen zurueck. */
    int lauf(Instant jetzt) {
        var zdt = jetzt.atZone(ZONE);
        if (zdt.getHour() < AB_STUNDE) return 0;
        LocalDate heute = zdt.toLocalDate();
        int gestartet = 0;
        for (AutoNachricht a : autoRepo.findAktive()) {
            Shop shop = a.getShop();
            if (shop == null || !shop.isActive()) continue;
            AutoNachrichtTyp typ;
            try {
                typ = AutoNachrichtTyp.von(a.getTyp());
            } catch (IllegalArgumentException e) {
                continue;
            }
            // Tageweise bzw. monatsweise: die Periode ist der Schluessel im Merker.
            LocalDate periode = typ.monatlich() ? heute.withDayOfMonth(1) : heute;
            String schluessel = shop.getId() + "|" + typ.name();
            if (periode.equals(geprueft.get(schluessel))) continue;
            Instant periodenbeginn = periode.atStartOfDay(ZONE).toInstant();
            try {
                if (kampagneRepo.existsByShopAndGruppeAndErstelltAmGreaterThanEqual(
                        shop, typ.gruppe().name(), periodenbeginn)) {
                    geprueft.put(schluessel, periode);
                    continue;
                }
                String text = a.getText() != null ? a.getText() : typ.standardText();
                Kampagne k = kampagnenService.starte(shop, text, typ.gruppe().name());
                markiere(k, typ, shop.getId(), jetzt);
                gestartet++;
                log.info("[AUTO] Laden {}: {} Kampagne {} an {} Karten", shop.getId(), typ, k.getId(), k.getEmpfaenger());
            } catch (IllegalArgumentException e) {
                // Niemand faellig (oder alle hatten heute schon eine Nachricht). Morgen wieder.
                log.debug("[AUTO] Laden {} {}: {}", shop.getId(), typ, e.getMessage());
            } catch (Exception e) {
                // Ein kaputter Laden haelt die anderen nicht auf.
                log.warn("[AUTO] Laden {} {} fehlgeschlagen: {}", shop.getId(), typ, e.getMessage());
                continue;
            }
            geprueft.put(schluessel, periode);
        }
        return gestartet;
    }

    /**
     * Merker setzen: auf die Empfaengerkarte, bei proPerson auch auf alle
     * weiteren Karten der Person bei diesem Laden.
     */
    void markiere(Kampagne k, AutoNachrichtTyp typ, String shopId, Instant jetzt) {
        if (typ == AutoNachrichtTyp.VIP) return;
        tx.executeWithoutResult(status -> {
            List<String> ids = empfaengerRepo.findByKampagneId(k.getId()).stream()
                    .map(KampagneEmpfaenger::getCustomerCardId).toList();
            for (CustomerCard cc : customerCardRepo.findAllById(ids)) {
                List<CustomerCard> karten = typ.proPerson()
                        ? customerCardRepo.findByCustomer(cc.getCustomer()) : List.of(cc);
                for (CustomerCard andere : karten) {
                    if (!shopId.equals(andere.getCard().getShop().getId())) continue;
                    if (typ.markiert(andere)) continue;
                    typ.markiere(andere, jetzt);
                    customerCardRepo.save(andere);
                }
            }
        });
    }
}
