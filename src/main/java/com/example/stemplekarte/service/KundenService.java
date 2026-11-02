package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * Kundenliste fuers Dashboard: wer hat eine Karte, wie steht sie, wann war
 * er zuletzt da. Dazu die Stempel-Korrektur ("mein Stempel fehlt").
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

    public KundenService(CustomerCardRepository customerCardRepo, AppleDeviceRepository deviceRepo) {
        this.customerCardRepo = customerCardRepo;
        this.deviceRepo = deviceRepo;
    }

    /** Eine Zeile der Liste. Zeiten als ISO-Text, null wenn unbekannt. */
    public record Kunde(String customerCardId, String customerId, String name, String email,
                        String cardId, String cardName, String type,
                        int stamps, int rewardThreshold, long pointsX100, String pointsText,
                        int totalRewards, String lastVisitAt, String createdAt,
                        boolean marketingConsent, boolean emailConfirmed,
                        boolean welcomePending, boolean apple,
                        String birthday, String phone) {}

    public record Seite(List<Kunde> items, int page, int totalPages, long totalItems) {}

    public Seite suche(Shop shop, String suche, int page, int size) {
        int groesse = Math.max(1, Math.min(SEITE_MAX, size));
        Page<CustomerCard> seite = customerCardRepo.findKunden(
                shop, muster(suche), PageRequest.of(Math.max(0, page), groesse));
        List<Kunde> items = seite.getContent().stream().map(this::kunde).toList();
        return new Seite(items, seite.getNumber(), seite.getTotalPages(), seite.getTotalElements());
    }

    /** "%wort%" in Kleinbuchstaben; leer = alles. Oeffentlich fuer den Repository-Test. */
    public static String muster(String suche) {
        if (suche == null) return "%";
        String s = suche.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? "%" : "%" + s + "%";
    }

    public Kunde kunde(CustomerCard cc) {
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
                cc.getPhone());
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
}
