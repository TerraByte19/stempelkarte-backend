package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.ToIntFunction;

/**
 * Kundenliste fuers Dashboard: wer hat eine Karte, wie steht sie, wann war
 * er zuletzt da, in welcher Gruppe steht er (Kundengruppe). Dazu die
 * Stempel-Korrektur ("mein Stempel fehlt") und die Gruppen-Regeln.
 *
 * Eine Korrektur ist kein Besuch: lastVisitAt bleibt stehen, es gibt keinen
 * ScanLog-Eintrag, die Statistik zaehlt keinen Scan. Genau wie beim Reset
 * im Scanner. Sonst saehe ein Laden, der zehn Karten nachtraegt, in der
 * Statistik einen Ansturm, der nie stattgefunden hat.
 */
@Service
public class KundenService {

    private static final Logger log = LoggerFactory.getLogger(KundenService.class);

    public static final int SEITE_MAX = 100;

    private final CustomerCardRepository customerCardRepo;
    private final AppleDeviceRepository deviceRepo;
    private final ScanLogRepository scanLogRepo;
    private final ShopRepository shopRepo;

    public KundenService(CustomerCardRepository customerCardRepo, AppleDeviceRepository deviceRepo,
                         ScanLogRepository scanLogRepo, ShopRepository shopRepo) {
        this.customerCardRepo = customerCardRepo;
        this.deviceRepo = deviceRepo;
        this.scanLogRepo = scanLogRepo;
        this.shopRepo = shopRepo;
    }

    /** Eine Zeile der Liste. Zeiten als ISO-Text, null wenn unbekannt. gruppe = Kundengruppe-Name. */
    public record Kunde(String customerCardId, String customerId, String name, String email,
                        String cardId, String cardName, String type,
                        int stamps, int rewardThreshold, long pointsX100, String pointsText,
                        int totalRewards, String lastVisitAt, String createdAt,
                        boolean marketingConsent, boolean emailConfirmed,
                        boolean welcomePending, boolean apple,
                        String birthday, String phone, String gruppe, String stufe,
                        // 10er-Karte: was vom Paket uebrig ist und wie viele Pakete gekauft wurden; sonst null.
                        Integer paketRest, Integer paketeGekauft) {}

    public record Seite(List<Kunde> items, int page, int totalPages, long totalItems) {}

    /** Kundenkarten je Gruppe (alle fuenf, auch mit 0) und die Regeln des Ladens. */
    public record Gruppen(Map<String, Long> zahlen, Kundengruppe.Regeln regeln) {}

    public Seite suche(Shop shop, String suche, int page, int size) {
        return suche(shop, suche, null, page, size);
    }

    public Seite suche(Shop shop, String suche, Kundengruppe gruppe, int page, int size) {
        return suche(shop, suche, gruppe, page, size, Instant.now());
    }

    /**
     * Ohne Gruppe der alte Weg: Suche und Seiten in der Datenbank. Mit
     * Gruppe werden alle aktiven Karten geladen und in Java eingeordnet,
     * gesucht und geschnitten - die Gruppe haengt an Regeln und Besuchen,
     * das laesst sich nicht sinnvoll in JPQL ausdruecken. Gleiche
     * Sortierung (zuletzt gesehen zuerst), gleiche Seitengroesse.
     */
    Seite suche(Shop shop, String suche, Kundengruppe gruppe, int page, int size, Instant jetzt) {
        int groesse = Math.max(1, Math.min(SEITE_MAX, size));
        int nummer = Math.max(0, page);
        Kundengruppe.Regeln regeln = Kundengruppe.Regeln.von(shop);

        if (gruppe == null) {
            Page<CustomerCard> seite = customerCardRepo.findKunden(shop, muster(suche), PageRequest.of(nummer, groesse));
            ToIntFunction<CustomerCard> besuche = besuche(shop, seite.getContent());
            List<Kunde> items = seite.getContent().stream()
                    .map(cc -> kunde(cc, besuche.applyAsInt(cc), regeln, jetzt)).toList();
            return new Seite(items, seite.getNumber(), seite.getTotalPages(), seite.getTotalElements());
        }

        List<CustomerCard> alle = customerCardRepo.findAlleKunden(shop);
        ToIntFunction<CustomerCard> besuche = besuche(shop, alle);
        String wort = suchwort(suche);
        List<CustomerCard> treffer = alle.stream()
                .filter(cc -> Kundengruppe.von(cc, besuche.applyAsInt(cc), regeln, jetzt) == gruppe)
                .filter(cc -> passtSuche(cc, wort))
                .toList();
        int total = treffer.size();
        int seiten = (total + groesse - 1) / groesse;
        int von = Math.min(nummer * groesse, total);
        int bis = Math.min(von + groesse, total);
        List<Kunde> items = treffer.subList(von, bis).stream()
                .map(cc -> kunde(cc, besuche.applyAsInt(cc), regeln, jetzt)).toList();
        return new Seite(items, nummer, seiten, total);
    }

    /** "%wort%" in Kleinbuchstaben; leer = alles. Oeffentlich fuer den Repository-Test. */
    public static String muster(String suche) {
        String s = suchwort(suche);
        return s.isEmpty() ? "%" : "%" + s + "%";
    }

    private static String suchwort(String suche) {
        return suche == null ? "" : suche.trim().toLowerCase(Locale.ROOT);
    }

    /** Dieselbe Regel wie findKunden: Name oder E-Mail enthaelt das Wort. */
    private static boolean passtSuche(CustomerCard cc, String wort) {
        if (wort.isEmpty()) return true;
        String name = cc.getCustomer().getName(), email = cc.getCustomer().getEmail();
        return (name != null && name.toLowerCase(Locale.ROOT).contains(wort))
                || (email != null && email.toLowerCase(Locale.ROOT).contains(wort));
    }

    // ── Kundengruppen ───────────────────────────────────────────────────

    public Gruppen gruppen(Shop shop) {
        return gruppen(shop, Instant.now());
    }

    Gruppen gruppen(Shop shop, Instant jetzt) {
        Kundengruppe.Regeln regeln = Kundengruppe.Regeln.von(shop);
        List<CustomerCard> alle = customerCardRepo.findAlleKunden(shop);
        ToIntFunction<CustomerCard> besuche = besuche(shop, alle);
        Map<String, Long> zahlen = new LinkedHashMap<>();
        for (Kundengruppe g : Kundengruppe.values()) zahlen.put(g.name(), 0L);
        for (CustomerCard cc : alle) {
            zahlen.merge(Kundengruppe.von(cc, besuche.applyAsInt(cc), regeln, jetzt).name(), 1L, Long::sum);
        }
        return new Gruppen(zahlen, regeln);
    }

    /** Regeln pruefen und speichern. Gibt den frischen Laden zurueck. */
    @Transactional
    public Shop updateRegeln(String shopId, Kundengruppe.Regeln regeln) {
        regeln.pruefe();
        Shop shop = shopRepo.findById(shopId)
                .orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
        shop.updateKundengruppen(regeln.stammAb(), regeln.ruhigTage(), regeln.verlorenTage());
        return shopRepo.save(shop);
    }

    /**
     * Besuche je Kundenkarte. Stempelkarten rechnen aus Stempeln und
     * Praemien, Punktekarten brauchen das ScanLog - eine Gruppen-Abfrage je
     * Laden, und nur, wenn ueberhaupt eine Punktekarte in der Liste ist.
     */
    private ToIntFunction<CustomerCard> besuche(Shop shop, List<CustomerCard> karten) {
        boolean punkte = karten.stream().anyMatch(cc -> cc.getCard().isPoints());
        Map<String, Long> scans = punkte ? scansJeKarte(shop) : Map.of();
        return cc -> cc.getCard().isPoints()
                ? klein(scans.getOrDefault(schluessel(cc.getCustomer().getId(), cc.getCard().getId()), 0L))
                : Kundengruppe.besucheStempelkarte(cc.getCard(), cc);
    }

    private Map<String, Long> scansJeKarte(Shop shop) {
        Map<String, Long> out = new HashMap<>();
        for (Object[] zeile : scanLogRepo.zaehleJeKundenkarte(shop.getId())) {
            out.put(schluessel(String.valueOf(zeile[0]), String.valueOf(zeile[1])), ((Number) zeile[2]).longValue());
        }
        return out;
    }

    private static String schluessel(String customerId, String cardId) {
        return customerId + "|" + cardId;
    }

    private static int klein(long n) {
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    // ── Zeile ───────────────────────────────────────────────────────────

    /** Einzelne Zeile (nach der Korrektur): Regeln vom Laden der Karte, Besuche einzeln nachgezaehlt. */
    public Kunde kunde(CustomerCard cc) {
        Card card = cc.getCard();
        int besuche = card.isPoints()
                ? klein(scanLogRepo.countByCardIdAndCustomerId(card.getId(), cc.getCustomer().getId()))
                : Kundengruppe.besucheStempelkarte(card, cc);
        return kunde(cc, besuche, Kundengruppe.Regeln.von(card.getShop()), Instant.now());
    }

    private Kunde kunde(CustomerCard cc, int besuche, Kundengruppe.Regeln regeln, Instant jetzt) {
        Card card = cc.getCard();
        Instant besuch = cc.getLastVisitAt();
        return new Kunde(cc.getId(), cc.getCustomer().getId(),
                cc.getCustomer().getName(), cc.getCustomer().getEmail(),
                card.getId(), card.getName(), card.getType().name(),
                cc.getStamps(), card.getRewardThreshold(),
                cc.getPointsX100(), PointsMath.formatiere(cc.getPointsX100()),
                cc.getTotalRewards(),
                besuch != null ? besuch.toString() : null,
                cc.getCreatedAt() != null ? cc.getCreatedAt().toString() : null,
                cc.isMarketingConsent(), cc.getCustomer().isEmailConfirmed(),
                cc.isWillkommenOffen(),
                !deviceRepo.findBySerialNumber(cc.getId()).isEmpty(),
                cc.getBirthday() != null ? cc.getBirthday().toString() : null,
                cc.getPhone(),
                Kundengruppe.von(cc, besuche, regeln, jetzt).name(),
                stufenName(card, cc),
                card.isPackage() ? cc.getPaketRest() : null,
                card.isPackage() ? cc.getPaketeGekauft() : null);
    }

    /** Bronze, Silber, Gold - oder null, wenn die Karte keine Stufen hat. */
    private static String stufenName(Card card, CustomerCard cc) {
        Stufen.Stufe s = Stufen.von(card, cc);
        return s != null ? s.name() : null;
    }

    // Bewusst KEIN CSV-Export: die Liste in der App deckt den Alltag ab
    // (Nachsehen, Korrigieren, Auskunft). Eine Datei auf dem Laptop des
    // Ladens ist eine Datenpanne in Wartestellung und umgeht die Regel, dass
    // Werbung nur an Kunden mit Einwilligung geht. Falls ein Laden ihn
    // wirklich braucht: Admin-Freischaltung pro Laden plus Protokoll.

    /**
     * Stand einer Stempelkarte von Hand setzen, 0 bis Schwelle. Fremde
     * Karten gelten als nicht vorhanden (404), damit sich ueber den Fehler
     * nicht ablesen laesst, welche IDs es gibt.
     */
    @Transactional
    public CustomerCard korrigiereStempel(Shop shop, String customerCardId, int neu) {
        CustomerCard cc = customerCardRepo.findById(customerCardId)
                .filter(c -> c.getCard().getShop().getId().equals(shop.getId()))
                .orElseThrow(() -> new NoSuchElementException("Kundenkarte nicht gefunden"));
        Card card = cc.getCard();
        if (card.isPoints()) {
            throw new IllegalArgumentException("Punkte korrigierst du im Scanner, nicht hier");
        }
        if (card.isMember()) {
            throw new IllegalArgumentException("Eine Mitgliedskarte hat keine Stempel");
        }
        if (card.isPackage()) {
            throw new IllegalArgumentException("Bei der 10er-Karte korrigierst du das Guthaben, nicht Stempel");
        }
        if (cc.isWillkommenOffen()) {
            throw new IllegalArgumentException("Die Karte ist noch gesperrt. Erst das Willkommensgeschenk an der Kasse einlösen.");
        }
        int max = card.getRewardThreshold();
        if (neu < 0 || neu > max) {
            throw new IllegalArgumentException("Stempel müssen zwischen 0 und " + max + " liegen");
        }
        if (neu == cc.getStamps()) return cc;
        int vorher = cc.getStamps();
        cc.korrigiereStempel(neu);
        customerCardRepo.save(cc);
        log.info("[KUNDEN] Stempel korrigiert: Karte {} Laden {} {} -> {}", cc.getId(), shop.getId(), vorher, neu);
        return cc;
    }

    /**
     * Guthaben einer 10er-Karte von Hand setzen ("ein Besuch wurde doppelt
     * abgebucht"). Kein Besuch, kein gekauftes Paket - dafuer gibt es
     * PaketService#aufladen.
     */
    @Transactional
    public CustomerCard korrigierePaket(Shop shop, String customerCardId, int neu) {
        CustomerCard cc = customerCardRepo.findById(customerCardId)
                .filter(c -> c.getCard().getShop().getId().equals(shop.getId()))
                .orElseThrow(() -> new NoSuchElementException("Kundenkarte nicht gefunden"));
        if (!cc.getCard().isPackage()) {
            throw new IllegalArgumentException("Guthaben gibt es nur bei der 10er-Karte");
        }
        if (neu < 0 || neu > PaketService.REST_MAX) {
            throw new IllegalArgumentException("Guthaben muss zwischen 0 und " + PaketService.REST_MAX + " liegen");
        }
        if (neu == cc.getPaketRest()) return cc;
        int vorher = cc.getPaketRest();
        cc.korrigierePaketRest(neu);
        customerCardRepo.save(cc);
        log.info("[KUNDEN] Paket korrigiert: Karte {} Laden {} {} -> {}", cc.getId(), shop.getId(), vorher, neu);
        return cc;
    }
}
