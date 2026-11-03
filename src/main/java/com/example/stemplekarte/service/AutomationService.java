package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Automation;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AutomationRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.KampagneEmpfaengerRepository;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Baukasten: eigene Automationen des Ladens (model.Automation). Der Laeufer
 * prueft ab 10 Uhr einmal am Tag je Automation, wer dran ist, und startet
 * dafuer eine Kampagne mit fertiger Empfaengerliste (Gruppe AUTOMATION).
 *
 * Wer schon dran war, kommt aus den Empfaengerzeilen frueherer Kampagnen
 * dieser Automation (letzter Versand je Karte). Je Ausloeser gilt: einmal je
 * Karte, je Pause, je Runde, je Jahr oder je Paket - siehe passt().
 *
 * Dieselben Schutzregeln wie bei jeder Kampagne: eine Karte je Person und
 * hoechstens eine Nachricht je Person und Tag.
 */
@Service
public class AutomationService {

    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    static final int AB_STUNDE = 10;
    public static final int MAX_JE_LADEN = 10;
    /** "n Tage nach der Anmeldung": wird innerhalb dieser Tage danach nachgeholt, sonst nie. */
    static final int FENSTER_TAGE = 3;
    /** Geburtstag: nach dem Versand ein Jahr Ruhe (mit Luft fuer schiefe Tage). */
    static final int GEBURTSTAG_RUHE_TAGE = 300;

    private final AutomationRepository automationRepo;
    private final ShopRepository shopRepo;
    private final CardRepository cardRepo;
    private final KampagneRepository kampagneRepo;
    private final KampagneEmpfaengerRepository empfaengerRepo;
    private final CustomerCardRepository customerCardRepo;
    private final KampagnenService kampagnenService;

    // Automation-ID -> Tag, an dem zuletzt geprueft wurde.
    private final Map<String, LocalDate> geprueft = new ConcurrentHashMap<>();

    public AutomationService(AutomationRepository automationRepo, ShopRepository shopRepo, CardRepository cardRepo,
                             KampagneRepository kampagneRepo, KampagneEmpfaengerRepository empfaengerRepo,
                             CustomerCardRepository customerCardRepo, KampagnenService kampagnenService) {
        this.automationRepo = automationRepo;
        this.shopRepo = shopRepo;
        this.cardRepo = cardRepo;
        this.kampagneRepo = kampagneRepo;
        this.empfaengerRepo = empfaengerRepo;
        this.customerCardRepo = customerCardRepo;
        this.kampagnenService = kampagnenService;
    }

    // ── Verwaltung ──────────────────────────────────────────────────────

    /** trifftJetzt = Personen, die beim naechsten Lauf dran waeren (ohne Tageslimit). */
    public record Eintrag(String id, String name, boolean aktiv, String ausloeser, String ausloeserTitel,
                          int wert, String cardId, String gruppe, String text,
                          String erstellt, String zuletzt, int gesendet, long trifftJetzt) {}

    /** Null = Feld nicht anfassen (beim Anlegen: Standard). cardId/gruppe "" = alle. */
    public record Anfrage(String name, String ausloeser, Integer wert, String cardId, String gruppe,
                          String text, Boolean aktiv) {}

    @Transactional(readOnly = true)
    public List<Eintrag> liste(Shop shop, Instant jetzt) {
        List<Automation> alle = automationRepo.findByShopOrderByErstelltAmAsc(shop);
        if (alle.isEmpty()) return List.of();
        List<CustomerCard> basis = customerCardRepo.findKampagnenBasis(shop);
        Kundengruppe.Regeln regeln = Kundengruppe.Regeln.von(shop);
        List<Eintrag> out = new ArrayList<>();
        for (Automation a : alle) {
            Map<String, Instant> zuletzt = letzterVersand(a.getId());
            long trifft = faellige(a, basis, jetzt, zuletzt, regeln, false).size();
            out.add(eintrag(a, trifft));
        }
        return out;
    }

    @Transactional
    public Eintrag anlegen(String shopId, Anfrage f) {
        Shop shop = shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
        if (automationRepo.countByShop(shop) >= MAX_JE_LADEN) {
            throw new IllegalArgumentException("Höchstens " + MAX_JE_LADEN + " Automationen je Laden");
        }
        AutomationAusloeser ausloeser = AutomationAusloeser.von(f.ausloeser());
        Instant jetzt = Instant.now();
        Automation a = Automation.neu(shop, nameSauber(f.name()), ausloeser.name(), ausloeser.pruefeWert(f.wert()),
                karteSauber(shop, f.cardId()), gruppeSauber(f.gruppe()), textSauber(f.text()),
                f.aktiv() == null || f.aktiv(), jetzt);
        a = automationRepo.save(a);
        log.info("[AUTOMATION] Laden {}: {} angelegt ({} {})", shopId, a.getId(), a.getAusloeser(), a.getWert());
        return eintrag(a, 0);
    }

    @Transactional
    public Eintrag aendern(String shopId, String id, Anfrage f) {
        Shop shop = shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
        Automation a = automationRepo.findByIdAndShop(id, shop)
                .orElseThrow(() -> new NoSuchElementException("Automation nicht gefunden"));
        AutomationAusloeser ausloeser = f.ausloeser() != null
                ? AutomationAusloeser.von(f.ausloeser()) : AutomationAusloeser.von(a.getAusloeser());
        // Bereich immer gegen den (neuen) Ausloeser pruefen.
        Integer wert = (f.wert() != null || f.ausloeser() != null)
                ? ausloeser.pruefeWert(f.wert() != null ? f.wert() : a.getWert()) : null;
        a.aendern(f.name() != null ? nameSauber(f.name()) : null,
                f.ausloeser() != null ? ausloeser.name() : null,
                wert,
                f.cardId() != null ? (f.cardId().isBlank() ? "" : karteSauber(shop, f.cardId())) : null,
                f.gruppe() != null ? (f.gruppe().isBlank() ? "" : gruppeSauber(f.gruppe())) : null,
                f.text() != null ? textSauber(f.text()) : null,
                f.aktiv(), Instant.now());
        a = automationRepo.save(a);
        log.info("[AUTOMATION] Laden {}: {} geändert, aktiv={}", shopId, a.getId(), a.isAktiv());
        return eintrag(a, 0);
    }

    @Transactional
    public void loeschen(String shopId, String id) {
        Shop shop = shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
        Automation a = automationRepo.findByIdAndShop(id, shop)
                .orElseThrow(() -> new NoSuchElementException("Automation nicht gefunden"));
        automationRepo.delete(a);
        log.info("[AUTOMATION] Laden {}: {} gelöscht", shopId, id);
    }

    private Eintrag eintrag(Automation a, long trifft) {
        String titel;
        try {
            titel = AutomationAusloeser.von(a.getAusloeser()).titel();
        } catch (IllegalArgumentException e) {
            titel = a.getAusloeser();
        }
        return new Eintrag(a.getId(), a.getName(), a.isAktiv(), a.getAusloeser(), titel, a.getWert(),
                a.getCardId(), a.getGruppe(), a.getText(),
                a.getErstelltAm() != null ? a.getErstelltAm().toString() : null,
                a.getZuletztAm() != null ? a.getZuletztAm().toString() : null,
                a.getGesendet(), trifft);
    }

    static String nameSauber(String name) {
        String n = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").trim();
        if (n.isEmpty()) throw new IllegalArgumentException("Name fehlt");
        if (n.length() > Automation.NAME_MAX) {
            throw new IllegalArgumentException("Name höchstens " + Automation.NAME_MAX + " Zeichen");
        }
        return n;
    }

    static String textSauber(String text) {
        String t = KampagnenService.sauber(text);
        if (t.isEmpty()) throw new IllegalArgumentException("Der Text darf nicht leer sein");
        if (t.length() > Kampagne.TEXT_MAX) {
            throw new IllegalArgumentException("Der Text darf höchstens " + Kampagne.TEXT_MAX + " Zeichen haben");
        }
        return t;
    }

    /** Null fuer alle; sonst muss die Karte dem Laden gehoeren. */
    private String karteSauber(Shop shop, String cardId) {
        if (cardId == null || cardId.isBlank()) return null;
        Card card = cardRepo.findById(cardId.trim()).orElse(null);
        if (card == null || card.getShop() == null || !shop.getId().equals(card.getShop().getId())) {
            throw new IllegalArgumentException("Karte nicht gefunden");
        }
        return card.getId();
    }

    /** Null fuer alle; sonst ein Kundengruppen-Name. */
    static String gruppeSauber(String gruppe) {
        if (gruppe == null || gruppe.isBlank()) return null;
        return Kundengruppe.von(gruppe).name();
    }

    // ── Regeln ──────────────────────────────────────────────────────────

    /**
     * Ist diese Karte jetzt dran? zuletzt = wann diese Automation der Karte
     * zuletzt geschrieben hat (null = nie).
     */
    static boolean passt(Automation a, CustomerCard cc, Instant jetzt, Instant zuletzt, Kundengruppe.Regeln regeln) {
        Card card = cc.getCard();
        if (card == null || cc.getCustomer() == null) return false;
        if (a.getCardId() != null && !a.getCardId().equals(card.getId())) return false;
        if (a.getGruppe() != null) {
            Kundengruppe g = Kundengruppe.von(cc, Kundengruppe.besucheStempelkarte(card, cc), regeln, jetzt);
            if (!g.name().equals(a.getGruppe())) return false;
        }
        AutomationAusloeser ausloeser;
        try {
            ausloeser = AutomationAusloeser.von(a.getAusloeser());
        } catch (IllegalArgumentException e) {
            return false;
        }
        int n = a.getWert();
        Instant besuch = cc.letzterBesuch();
        return switch (ausloeser) {
            case NACH_ANMELDUNG -> zuletzt == null && cc.getCreatedAt() != null
                    && !cc.getCreatedAt().isAfter(jetzt.minus(Duration.ofDays(n)))
                    && cc.getCreatedAt().isAfter(jetzt.minus(Duration.ofDays(n + FENSTER_TAGE)));
            case KEIN_BESUCH_SEIT -> besuch != null && besuch.isBefore(jetzt.minus(Duration.ofDays(n)))
                    && (zuletzt == null || zuletzt.isBefore(besuch));
            case BESUCH_NR -> zuletzt == null && !card.isPoints() && besuch != null
                    && Kundengruppe.besucheStempelkarte(card, cc) >= n && tagDanach(besuch, jetzt);
            case STEMPEL_STAND -> card.isStamp() && cc.getStamps() == n && besuch != null
                    && tagDanach(besuch, jetzt) && (zuletzt == null || zuletzt.isBefore(besuch));
            case GEBURTSTAG_IN_TAGEN -> cc.getBirthday() != null
                    && geburtstagInTagen(cc.getBirthday(), jetzt.atZone(ZONE).toLocalDate(), n)
                    && (zuletzt == null || zuletzt.isBefore(jetzt.minus(Duration.ofDays(GEBURTSTAG_RUHE_TAGE))));
            case PAKET_REST -> card.isPackage() && cc.getPaketeGekauft() >= 1 && cc.getPaketRest() == n
                    && besuch != null && tagDanach(besuch, jetzt)
                    && (zuletzt == null || cc.getPaketAufgeladenAm() == null
                        || zuletzt.isBefore(cc.getPaketAufgeladenAm()));
        };
    }

    /** Wie die festen Automaten: 20 Stunden bis 3 Tage nach dem Besuch. */
    static boolean tagDanach(Instant besuch, Instant jetzt) {
        return !besuch.isAfter(jetzt.minus(Duration.ofHours(Zielgruppe.AUTO_MIN_STUNDEN)))
                && !besuch.isBefore(jetzt.minus(Duration.ofDays(Zielgruppe.AUTO_MAX_TAGE)));
    }

    /** Geburtstag faellt auf heute + n Tage (29. Februar in Nicht-Schaltjahren: 28.). */
    static boolean geburtstagInTagen(LocalDate geburtstag, LocalDate heute, int n) {
        LocalDate ziel = heute.plusDays(n);
        return geburtstag.getMonth() == ziel.getMonth()
                && (geburtstag.getDayOfMonth() == ziel.getDayOfMonth()
                    || (geburtstag.getDayOfMonth() == 29 && ziel.getDayOfMonth() == 28 && !ziel.isLeapYear()));
    }

    /**
     * Faellige Karten: eine je Person (die zuletzt benutzte), und bei
     * tageslimit = true ohne die, die heute schon eine Nachricht bekamen.
     */
    List<CustomerCard> faellige(Automation a, List<CustomerCard> basis, Instant jetzt,
                                Map<String, Instant> zuletzt, Kundengruppe.Regeln regeln, boolean tageslimit) {
        Set<String> heute = tageslimit
                ? KampagnenService.heuteBenachrichtigt(basis, KampagnenService.tagesbeginn(jetzt)) : Set.of();
        Map<String, CustomerCard> proKunde = new LinkedHashMap<>();
        for (CustomerCard cc : basis) {
            if (!passt(a, cc, jetzt, zuletzt.get(cc.getId()), regeln)) continue;
            String kunde = cc.getCustomer().getId();
            if (heute.contains(kunde)) continue;
            proKunde.merge(kunde, cc, KampagnenService::zuletztBenutzt);
        }
        return List.copyOf(proKunde.values());
    }

    private Map<String, Instant> letzterVersand(String automationId) {
        Map<String, Instant> out = new HashMap<>();
        for (Object[] zeile : empfaengerRepo.letzterVersandJeKarte(automationId)) {
            if (zeile[0] != null && zeile[1] instanceof Instant am) out.put((String) zeile[0], am);
        }
        return out;
    }

    // ── Laeufer ─────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 5 * 60_000)
    public void lauf() {
        try {
            lauf(Instant.now());
        } catch (Exception e) {
            log.warn("[AUTOMATION] Lauf abgebrochen: {}", e.getMessage());
        }
    }

    /** Paketsichtbar mit Zeitpunkt fuer den Test. Gibt gestartete Kampagnen zurueck. */
    int lauf(Instant jetzt) {
        var zdt = jetzt.atZone(ZONE);
        if (zdt.getHour() < AB_STUNDE) return 0;
        LocalDate heute = zdt.toLocalDate();
        Instant tagesbeginn = heute.atStartOfDay(ZONE).toInstant();
        int gestartet = 0;
        for (Automation a : automationRepo.findByAktivTrue()) {
            Shop shop = a.getShop();
            if (shop == null || !shop.isActive()) continue;
            if (heute.equals(geprueft.get(a.getId()))) continue;
            try {
                if (kampagneRepo.existsByAutomationIdAndErstelltAmGreaterThanEqual(a.getId(), tagesbeginn)) {
                    geprueft.put(a.getId(), heute);
                    continue;
                }
                List<CustomerCard> basis = customerCardRepo.findKampagnenBasis(shop);
                List<CustomerCard> empf = faellige(a, basis, jetzt, letzterVersand(a.getId()),
                        Kundengruppe.Regeln.von(shop), true);
                if (!empf.isEmpty()) {
                    Kampagne k = kampagnenService.starteFuer(shop, a.getText(), empf, a.getId());
                    a.gelaufen(jetzt, k.getEmpfaenger());
                    automationRepo.save(a);
                    gestartet++;
                    log.info("[AUTOMATION] Laden {}: {} Kampagne {} an {} Karten",
                            shop.getId(), a.getId(), k.getId(), k.getEmpfaenger());
                }
            } catch (IllegalArgumentException e) {
                log.debug("[AUTOMATION] {}: {}", a.getId(), e.getMessage());
            } catch (Exception e) {
                // Eine kaputte Automation haelt die anderen nicht auf.
                log.warn("[AUTOMATION] {} fehlgeschlagen: {}", a.getId(), e.getMessage());
                continue;
            }
            geprueft.put(a.getId(), heute);
        }
        return gestartet;
    }
}
