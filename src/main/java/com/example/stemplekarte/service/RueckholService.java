package com.example.stemplekarte.service;

import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.wallet.ApnsPushService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Rueckhol-Nachricht: wer eine Weile nicht da war, bekommt einmal eine
 * Nachricht auf seine Wallet-Karte ("Wir vermissen dich! Noch 3 Stempel bis
 * ..."). Pro Laden abschaltbar, Standard aus.
 *
 * Apple: kein eigener Push-Text, sondern ein Rueckseitenfeld mit
 * changeMessage (ApplePassService#nachrichtFeld). iOS meldet das nur sicher,
 * wenn der Pass auf dem Handy das Feld schon kennt. Paesse von vor dem
 * Einschalten bekommen es deshalb zuerst still nachgereicht und die
 * Nachricht einen Lauf spaeter.
 *
 * Google: eigene Nachricht mit Benachrichtigung (TEXT_AND_NOTIFY).
 *
 * Laeuft stuendlich zwischen 10 und 19 Uhr deutscher Zeit - nachts klingelt
 * nichts, und schlaeft die Instanz zur vollen Stunde, holt der naechste Lauf
 * es nach. Doppelt verschickt wird nichts: die Karte merkt sich die
 * Nachricht bis zum naechsten Besuch.
 */
@Service
public class RueckholService {

    private static final Logger log = LoggerFactory.getLogger(RueckholService.class);

    public static final List<Integer> ERLAUBTE_TAGE = List.of(14, 21, 30);
    public static final int STANDARD_TAGE = 14;

    // Je Laden und Lauf. Jede Karte, die sich aendert, holt sich iOS neu ab,
    // und ein Pass-Bau kostet auf der kleinen Instanz 1-3 s. Schaltet ein
    // grosser Laden die Funktion ein, verteilt sich der Rueckstand so ueber
    // die Laeufe des Tages, statt die Instanz auf einen Schlag zu fluten.
    static final int MAX_PRO_LAUF = 50;

    private final ShopRepository shopRepo;
    private final CustomerCardRepository customerCardRepo;
    private final AppleDeviceRepository deviceRepo;
    private final RewardService rewardService;
    private final ApnsPushService apnsPushService;
    private final GoogleWalletService googleWalletService;
    private final TransactionTemplate tx;

    public RueckholService(ShopRepository shopRepo, CustomerCardRepository customerCardRepo,
                           AppleDeviceRepository deviceRepo, RewardService rewardService,
                           ApnsPushService apnsPushService, GoogleWalletService googleWalletService,
                           TransactionTemplate tx) {
        this.shopRepo = shopRepo;
        this.customerCardRepo = customerCardRepo;
        this.deviceRepo = deviceRepo;
        this.rewardService = rewardService;
        this.apnsPushService = apnsPushService;
        this.googleWalletService = googleWalletService;
        this.tx = tx;
    }

    /** Tage des Ladens. Fehlen sie oder sind sie keine der erlaubten, gilt der Standard. */
    public static int tageFuer(Shop shop) {
        Integer d = shop.getWinbackDays();
        return (d != null && ERLAUBTE_TAGE.contains(d)) ? d : STANDARD_TAGE;
    }

    @Scheduled(cron = "0 0 10-19 * * *", zone = "Europe/Berlin")
    public void lauf() {
        Instant jetzt = Instant.now();
        for (Shop shop : shopRepo.findAll()) {
            if (!shop.isActive() || !shop.isRueckholAktiv()) continue;
            try {
                verschicke(shop, jetzt);
            } catch (Exception e) {
                // Ein kaputter Laden haelt die anderen nicht auf.
                log.warn("[RUECKHOL] Laden {} fehlgeschlagen: {}", shop.getId(), e.getMessage());
            }
        }
    }

    private record Versand(String customerCardId, String text, boolean nurAuffrischen) {}

    void verschicke(Shop shop, Instant jetzt) {
        Instant grenze = jetzt.minus(Duration.ofDays(tageFuer(shop)));
        // Erst festschreiben, dann pushen: holt iOS den Pass ab, bevor die
        // Transaktion durch ist, bekaeme es den alten Stand.
        List<Versand> versand = tx.execute(status -> markiere(shop, grenze, jetzt));
        if (versand == null || versand.isEmpty()) return;

        int nachrichten = 0;
        for (Versand v : versand) {
            if (v.nurAuffrischen()) {
                apnsPushService.notifyUpdate(v.customerCardId());
                continue;
            }
            nachrichten++;
            if (hatApple(v.customerCardId())) apnsPushService.notifyUpdate(v.customerCardId());
            googleWalletService.sendeNachricht(v.customerCardId(), shop.getName(), v.text());
        }
        log.info("[RUECKHOL] Laden {}: {} Nachrichten, {} Paesse still aufgefrischt",
                shop.getId(), nachrichten, versand.size() - nachrichten);
    }

    private List<Versand> markiere(Shop shop, Instant grenze, Instant jetzt) {
        List<Versand> versand = new ArrayList<>();
        List<CustomerCard> kandidaten = customerCardRepo.findRueckholKandidaten(
                shop, grenze, PageRequest.of(0, MAX_PRO_LAUF));
        for (CustomerCard cc : kandidaten) {
            if (!cc.rueckholFaellig(grenze)) continue;
            if (hatApple(cc.getId()) && !feldAufDemHandy(shop, cc)) {
                cc.passAuffrischen(jetzt);
                versand.add(new Versand(cc.getId(), null, true));
            } else {
                String text = text(shop, cc, rewardService);
                cc.rueckholGesendet(text, jetzt);
                versand.add(new Versand(cc.getId(), text, false));
            }
            customerCardRepo.save(cc);
        }
        return versand;
    }

    /**
     * Wurde der Pass nach dem Einschalten neu gebaut, traegt er das
     * Nachrichtenfeld. Jede Aenderung an der Karte schickt einen Push, und
     * iOS holt den Pass daraufhin ab.
     */
    static boolean feldAufDemHandy(Shop shop, CustomerCard cc) {
        Instant seit = shop.getWinbackEnabledAt();
        return seit != null && cc.getUpdatedAt() != null && cc.getUpdatedAt().isAfter(seit);
    }

    private boolean hatApple(String customerCardId) {
        return !deviceRepo.findBySerialNumber(customerCardId).isEmpty();
    }

    /** Text der Nachricht: eigener Text des Ladens mit Platzhaltern, sonst Standard. */
    static String text(Shop shop, CustomerCard cc, RewardService rewardService) {
        KartenFortschritt f = KartenFortschritt.von(cc.getCard(), cc, rewardService);
        String eigener = shop.getWinbackText();
        if (eigener != null && !eigener.isBlank()) {
            if (f != null) return f.ersetze(eigener);
            // Punktekarte ohne Praemie: Platzhalter liessen sich nicht fuellen.
            if (!eigener.contains("{")) return eigener;
        }
        if (f == null) return "Wir vermissen dich! Schau mal wieder vorbei.";
        return f.erreicht()
                ? "Wir vermissen dich! " + f.belohnung() + " wartet auf dich."
                : "Wir vermissen dich! Noch " + f.offen() + " " + f.einheit() + " bis: " + f.belohnung();
    }
}
