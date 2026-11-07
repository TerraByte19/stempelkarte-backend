package com.example.stemplekarte.service;

import com.example.stemplekarte.i18n.Texts;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Selbst-Stempeln am Tablet (Zugang mit Rolle KIOSK): der Kunde haelt seine
 * Karte vor die Kamera, ohne Personal. Damit das kein Stempel-Automat wird,
 * prueft dieser Dienst VOR dem Stempel:
 *
 * - nur Stempelkarten (Punkte, Mitglied, 10er: beim Personal)
 * - Willkommensgeschenk offen: erst beim Personal einloesen
 * - Karte voll: nicht stempeln, denn ein Scan auf einer vollen Karte wuerde
 *   die Praemie einloesen - die gibt das Personal
 * - Sperrzeit: nach einem Stempel (egal von wem) so viele Stunden Pause, wie
 *   der Laden eingestellt hat (Shop.kioskSperreStunden, Standard 2)
 *
 * stempeln() macht Pruefung und Stempel in EINER Transaktion mit Zeilensperre,
 * damit zwei Tablets mit derselben Karte im selben Moment nicht beide durch
 * die Pruefung kommen.
 *
 * Die Meldungen gehen an den Kunden, deshalb in seiner Sprache.
 * IllegalArgumentException = 400 mit Klartext, der Kiosk zeigt sie an.
 */
@Service
public class KioskService {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm");

    private final CustomerCardRepository customerCardRepo;
    private final CardRepository cardRepo;
    private final ScanLogRepository scanLogRepo;
    private final CustomerService customerService;

    public KioskService(CustomerCardRepository customerCardRepo, CardRepository cardRepo,
                        ScanLogRepository scanLogRepo, CustomerService customerService) {
        this.customerCardRepo = customerCardRepo;
        this.cardRepo = cardRepo;
        this.scanLogRepo = scanLogRepo;
        this.customerService = customerService;
    }

    /**
     * Der Kiosk-Stempel: Zeile sperren, pruefen, genau einen Stempel geben,
     * alles in einer Transaktion.
     *
     * Vorher lief pruefe() ohne Transaktion und processScan() danach in
     * einer eigenen. Zwei Tablets, die dieselbe Karte im selben Moment sahen,
     * kamen beide durch die Pruefung und gaben zwei Stempel. Jetzt wartet das
     * zweite an der Sperre, bis das erste fertig ist, sieht dessen Scan im
     * Protokoll und meldet die Sperrzeit.
     *
     * Die Sperre kommt VOR jedem Laden, auch vor QrPayload.parse mit
     * Datenbank: alles, was danach gelesen wird, zeigt den Stand nach dem
     * anderen Tablet. processScan laeuft in derselben Transaktion mit.
     */
    @Transactional
    public ScanResult stempeln(Shop shop, String qrPayload, Instant jetzt, String staffLabel, String ortId) {
        sperren(qrPayload);
        pruefe(shop, qrPayload, jetzt);
        return customerService.processScan(qrPayload, shop, 1, staffLabel, ortId);
    }

    /** Kurzer QR: ueber die Kundenkarten-ID. Langer QR: ueber Kunde und Karte. Beides ohne Laden. */
    private void sperren(String qrPayload) {
        String id = QrPayload.kurzeId(qrPayload);
        if (id != null) {
            customerCardRepo.sperrePerId(id);
            return;
        }
        QrPayload qr = QrPayload.parse(qrPayload == null ? "" : qrPayload.trim());
        customerCardRepo.sperrePerKundeUndKarte(qr.customerId(), qr.cardId());
    }

    /** Wirft, wenn der Kiosk NICHT stempeln darf. Sonst passiert nichts. */
    public void pruefe(Shop shop, String qrPayload, Instant jetzt) {
        QrPayload qr = QrPayload.parse(qrPayload, customerCardRepo);
        Card card = cardRepo.findById(qr.cardId())
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));
        if (card.getShop() == null || !card.getShop().getId().equals(shop.getId())) {
            throw new IllegalArgumentException("Diese Karte gehoert nicht zu deinem Shop");
        }
        Optional<CustomerCard> cc = customerCardRepo.findByCustomer_IdAndCard_Id(qr.customerId(), card.getId());
        String lang = Texts.sprache(cc.map(CustomerCard::getCustomer).orElse(null), shop);

        if (!card.isStamp()) {
            throw new IllegalArgumentException(Texts.t(lang, "kiosk.not_stamp"));
        }
        if (cc.isEmpty()) return;   // erste Karte: der Stempel legt sie an

        CustomerCard k = cc.get();
        if (k.isWillkommenOffen()) {
            throw new IllegalArgumentException(Texts.t(lang, "kiosk.welcome"));
        }
        if (k.getStamps() >= card.getRewardThreshold()) {
            throw new IllegalArgumentException(Texts.t(lang, "kiosk.full", card.getRewardText()));
        }
        Duration sperre = Duration.ofHours(shop.getKioskSperreStunden());
        scanLogRepo.findTopByShopIdAndCustomerIdAndCardIdOrderByScannedAtDesc(
                        shop.getId(), qr.customerId(), card.getId())
                .map(ScanLog::getScannedAt)
                .filter(letzter -> letzter != null && letzter.plus(sperre).isAfter(jetzt))
                .ifPresent(letzter -> {
                    String ab = UHR.format(letzter.plus(sperre).atZone(BERLIN));
                    throw new IllegalArgumentException(Texts.t(lang, "kiosk.locked", ab));
                });
    }
}
