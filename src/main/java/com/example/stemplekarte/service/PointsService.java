package com.example.stemplekarte.service;

import com.example.stemplekarte.model.BookingKind;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CardType;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.PointsBooking;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.PointsBookingRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Punktebuchungen. Jeder Weg aendert den Stand und schreibt genau eine
 * Buchung, in derselben Transaktion.
 *
 * Bewusst getrennt von CustomerService: der traegt den Stempel-Weg, der in
 * echten Laeden laeuft. Zwei Zaehler in einer Klasse waeren zwei Gruende,
 * dieselbe Datei anzufassen.
 *
 * Der ScanLog wird bei EARN und REDEEM mitgeschrieben, damit Stosszeiten
 * und aktive Kunden ueber beide Kartentypen stimmen. Korrektur und
 * Ruecknahme schreiben keinen - sie sind kein Kundenbesuch. Wie beim
 * Stempel-Weg darf ein Fehler dabei die Buchung nicht abbrechen.
 */
@Service
public class PointsService {

    private static final Logger log = LoggerFactory.getLogger(PointsService.class);

    private final CustomerCardRepository customerCardRepo;
    private final CardRepository cardRepo;
    private final PointsBookingRepository bookingRepo;
    private final ScanLogRepository scanLogRepo;
    private final RewardService rewardService;

    public PointsService(CustomerCardRepository customerCardRepo,
                         CardRepository cardRepo,
                         PointsBookingRepository bookingRepo,
                         ScanLogRepository scanLogRepo,
                         RewardService rewardService) {
        this.customerCardRepo = customerCardRepo;
        this.cardRepo = cardRepo;
        this.bookingRepo = bookingRepo;
        this.scanLogRepo = scanLogRepo;
        this.rewardService = rewardService;
    }

    // ── Ausgabe-Typen ─────────────────────────────────────────────────────
    // Bewusst OHNE JPA-Entitaeten. Sie werden innerhalb der Transaktion
    // gefuellt; der Controller reicht sie nur noch weiter. Ein Reward oder
    // eine CustomerCard hier durchzureichen hiesse, dem Controller einen
    // Lazy-Proxy in die Hand zu druecken - und mit open-in-view: false ist
    // die Session dort zu. Genau so ist am 12.09. jeder Scan nach dem
    // gesetzten Stempel abgestuerzt.

    public record RewardView(String id, String name, long costPointsX100,
                             String costText, boolean bezahlbar, long fehlendX100) {
        static RewardView von(Reward r, long standX100) {
            long fehlend = Math.max(0, r.getCostPointsX100() - standX100);
            return new RewardView(r.getId(), r.getName(), r.getCostPointsX100(),
                    PointsMath.formatiere(r.getCostPointsX100()), fehlend == 0, fehlend);
        }
    }

    /** Ohne staffLabel kaeme nicht heraus, welches Geraet gebucht hat. MIT
     *  dem Token waere die Zugangsberechtigung im Frontend ablesbar - der
     *  Token ist bei StaffToken zugleich Primaerschluessel und Berechtigung. */
    public record BookingView(String id, String kind, long deltaPointsX100,
                              String deltaText, Long amountCents, String rewardName,
                              String staffLabel, Instant createdAt) {
        static BookingView von(PointsBooking b) {
            if (b == null) return null;
            return new BookingView(b.getId(), b.getKind().name(), b.getDeltaPointsX100(),
                    PointsMath.formatiere(b.getDeltaPointsX100()), b.getAmountCents(),
                    b.getRewardName(), b.getStaffLabel(), b.getCreatedAt());
        }
    }

    /** Alles, was der Scanner nach dem Scannen braucht, um zu entscheiden,
     *  welche Oberflaeche er zeigt. pointsRounding ist dabei nicht optional:
     *  ohne sie kann die Live-Vorschau im Scanner nicht dasselbe rechnen wie
     *  die Buchung, und genau dieses Vertrauen soll sie herstellen. */
    public record PointsState(
            String customerCardId, String customerName,
            String cardId, String cardName, CardType type,
            long pointsX100, String pointsText,
            int stamps, int rewardThreshold, String rewardText,
            Integer pointsPerEuroX100, String pointsRounding,
            List<RewardView> katalog, RewardView ziel, long fehlendX100,
            String fehlendText, BookingView letzteBuchung) {}

    /** Ergebnis einer Buchung. */
    public record PointsResult(
            String customerCardId, String customerName,
            String cardId, String cardName,
            long pointsX100, String pointsText,
            BookingView booking, RewardView ziel,
            long fehlendX100, String fehlendText,
            boolean neuesZielErreicht, List<RewardView> katalog) {}

    // ── Zustand ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PointsState state(String qrPayload, Shop shop) {
        QrPayload qr = QrPayload.parse(qrPayload);
        Card card = ladeKarte(qr.cardId(), shop);
        CustomerCard cc = ladeKundenkarte(qr, card);

        if (!card.isPoints()) {
            // Stempelkarte: der Scanner braucht nur den Typ und den Stand.
            return new PointsState(cc.getId(), cc.getCustomer().getName(),
                    card.getId(), card.getName(), CardType.STAMP,
                    0, "0", cc.getStamps(), card.getRewardThreshold(), card.getRewardText(),
                    null, null, List.of(), null, 0, "0", null);
        }

        long stand = cc.getPointsX100();
        List<Reward> katalog = rewardService.list(card);
        Reward ziel = RewardService.naechstesZiel(katalog, stand);
        long fehlend = fehlend(ziel, stand);

        return new PointsState(cc.getId(), cc.getCustomer().getName(),
                card.getId(), card.getName(), CardType.POINTS,
                stand, PointsMath.formatiere(stand),
                0, 0, null,
                card.getPointsPerEuroX100(), card.getPointsRounding().name(),
                katalog.stream().map(r -> RewardView.von(r, stand)).toList(),
                ziel != null ? RewardView.von(ziel, stand) : null,
                fehlend, PointsMath.formatiere(fehlend),
                BookingView.von(letzteBuchung(cc.getId())));
    }

    // ── Buchen ────────────────────────────────────────────────────────────

    @Transactional
    public PointsResult earn(String qrPayload, Shop shop, long amountCents, String staffLabel) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException(
                    "Betrag muss groesser als null sein. Fuer einen Abzug die Korrektur benutzen.");
        }
        QrPayload qr = QrPayload.parse(qrPayload);
        Card card = ladePunktekarte(qr.cardId(), shop);
        CustomerCard cc = ladeKundenkarte(qr, card);

        int kurs = card.getPointsPerEuroX100();
        long punkte = PointsMath.punkteFuer(amountCents, kurs, card.getPointsRounding());

        long vorher = cc.getPointsX100();
        long gebucht = cc.addPoints(punkte);
        customerCardRepo.save(cc);

        PointsBooking booking = bookingRepo.save(
                PointsBooking.earn(cc, shop.getId(), gebucht, amountCents, kurs, staffLabel));

        scanLogSchreiben(shop, card, cc, 0);

        log.info("[PUNKTE] EARN karte={} betrag={} punkte={} stand={}",
                cc.getId(), amountCents, gebucht, cc.getPointsX100());

        return ergebnis(card, cc, booking, vorher);
    }

    @Transactional
    public PointsResult redeem(String qrPayload, Shop shop, String rewardId, String staffLabel) {
        QrPayload qr = QrPayload.parse(qrPayload);
        Card card = ladePunktekarte(qr.cardId(), shop);
        CustomerCard cc = ladeKundenkarte(qr, card);

        Reward reward = rewardService.getByIdAndCard(rewardId, card);
        if (!reward.isActive()) {
            throw new IllegalArgumentException("Diese Praemie gibt es nicht mehr");
        }
        if (!cc.kannBezahlen(reward.getCostPointsX100())) {
            throw new IllegalArgumentException(
                    "Nicht genug Punkte: %s von %s noetig".formatted(
                            PointsMath.formatiere(cc.getPointsX100()),
                            PointsMath.formatiere(reward.getCostPointsX100())));
        }

        long vorher = cc.getPointsX100();
        cc.addPoints(-reward.getCostPointsX100());
        cc.zaehleBelohnung();
        customerCardRepo.save(cc);

        PointsBooking booking = bookingRepo.save(
                PointsBooking.redeem(cc, shop.getId(), reward, staffLabel));

        scanLogSchreiben(shop, card, cc, 1);

        log.info("[PUNKTE] REDEEM karte={} praemie={} kosten={} stand={}",
                cc.getId(), reward.getName(), reward.getCostPointsX100(), cc.getPointsX100());

        return ergebnis(card, cc, booking, vorher);
    }

    @Transactional
    public PointsResult correct(String qrPayload, Shop shop,
                                Long amountCents, Long pointsX100, String staffLabel) {
        boolean hatBetrag = amountCents != null && amountCents != 0;
        boolean hatPunkte = pointsX100 != null && pointsX100 != 0;
        if (hatBetrag == hatPunkte) {
            throw new IllegalArgumentException(
                    "Korrektur braucht entweder einen Betrag oder eine Punktzahl, nicht beides");
        }

        QrPayload qr = QrPayload.parse(qrPayload);
        Card card = ladePunktekarte(qr.cardId(), shop);
        CustomerCard cc = ladeKundenkarte(qr, card);

        Integer kurs = hatBetrag ? card.getPointsPerEuroX100() : null;
        long delta = hatBetrag
                ? PointsMath.punkteFuer(amountCents, kurs, card.getPointsRounding())
                : pointsX100;

        long vorher = cc.getPointsX100();
        long gebucht = cc.addPoints(delta);
        customerCardRepo.save(cc);

        PointsBooking booking = bookingRepo.save(PointsBooking.correction(
                cc, shop.getId(), gebucht, hatBetrag ? amountCents : null, kurs, staffLabel));

        log.info("[PUNKTE] CORRECTION karte={} delta={} stand={} von={}",
                cc.getId(), gebucht, cc.getPointsX100(), staffLabel);

        return ergebnis(card, cc, booking, vorher);
    }

    @Transactional
    public PointsResult undo(String qrPayload, Shop shop, String bookingId, String staffLabel) {
        QrPayload qr = QrPayload.parse(qrPayload);
        Card card = ladePunktekarte(qr.cardId(), shop);
        CustomerCard cc = ladeKundenkarte(qr, card);

        PointsBooking original = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new NoSuchElementException("Buchung nicht gefunden"));

        // Fremde Buchung: darf nicht ueber eine andere Karte zurueckgenommen
        // werden, sonst greift ein Laden in die Karte eines anderen.
        if (!original.getCustomerCardId().equals(cc.getId())
                || !original.getShopId().equals(shop.getId())) {
            throw new IllegalArgumentException("Buchung gehoert nicht zu dieser Karte");
        }
        if (original.istZurueckgenommen()) {
            throw new IllegalArgumentException("Diese Buchung wurde bereits zurueckgenommen");
        }
        if (original.getKind() == BookingKind.REVERSAL) {
            throw new IllegalArgumentException(
                    "Eine Gegenbuchung laesst sich nicht zuruecknehmen. Neu buchen.");
        }

        long vorher = cc.getPointsX100();
        long gebucht = cc.addPoints(-original.getDeltaPointsX100());
        if (original.getKind() == BookingKind.REDEEM) {
            // Die Praemie war nie eingeloest, also zaehlt sie auch nicht.
            cc.nimmBelohnungZurueck();
        }
        customerCardRepo.save(cc);

        original.markiereAlsZurueckgenommen();
        bookingRepo.save(original);

        PointsBooking booking = bookingRepo.save(
                PointsBooking.reversal(cc, shop.getId(), original, gebucht, staffLabel));

        log.info("[PUNKTE] REVERSAL karte={} zuBuchung={} delta={} stand={}",
                cc.getId(), original.getId(), gebucht, cc.getPointsX100());

        return ergebnis(card, cc, booking, vorher);
    }

    // ── Helfer ────────────────────────────────────────────────────────────

    private PointsResult ergebnis(Card card, CustomerCard cc,
                                  PointsBooking booking, long standVorher) {
        long stand = cc.getPointsX100();
        List<Reward> katalog = rewardService.list(card);
        Reward ziel = RewardService.naechstesZiel(katalog, stand);
        long fehlend = fehlend(ziel, stand);

        // "Neues Ziel erreicht" heisst: es ist jetzt eine Praemie bezahlbar,
        // die es vorher nicht war. Nur dann meldet sich der Pass - sonst
        // wuerde die Sperrbildschirm-Meldung bei jeder Buchung aufpoppen und
        // zum Rauschen werden.
        long bezahlbarVorher = katalog.stream()
                .filter(r -> r.getCostPointsX100() <= standVorher).count();
        long bezahlbarJetzt = katalog.stream()
                .filter(r -> r.getCostPointsX100() <= stand).count();

        // Alles Abbilden passiert HIER, innerhalb der Transaktion. Was der
        // Controller bekommt, ist frei von Lazy-Proxies.
        return new PointsResult(
                cc.getId(), cc.getCustomer().getName(),
                card.getId(), card.getName(),
                stand, PointsMath.formatiere(stand),
                BookingView.von(booking),
                ziel != null ? RewardView.von(ziel, stand) : null,
                fehlend, PointsMath.formatiere(fehlend),
                bezahlbarJetzt > bezahlbarVorher,
                katalog.stream().map(r -> RewardView.von(r, stand)).toList());
    }

    private long fehlend(Reward ziel, long standX100) {
        if (ziel == null) return 0;
        return Math.max(0, ziel.getCostPointsX100() - standX100);
    }

    private PointsBooking letzteBuchung(String customerCardId) {
        return bookingRepo.findTop10ByCustomerCardIdOrderByCreatedAtDesc(customerCardId)
                .stream()
                .filter(b -> !b.istZurueckgenommen() && b.getKind() != BookingKind.REVERSAL)
                .findFirst()
                .orElse(null);
    }

    private Card ladeKarte(String cardId, Shop shop) {
        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));
        if (!card.getShop().getId().equals(shop.getId())) {
            throw new IllegalArgumentException("Diese Karte gehoert nicht zu deinem Shop");
        }
        if (!card.isActive()) {
            throw new IllegalArgumentException("Diese Karte ist nicht mehr aktiv");
        }
        return card;
    }

    private Card ladePunktekarte(String cardId, Shop shop) {
        Card card = ladeKarte(cardId, shop);
        if (!card.isPoints()) {
            throw new IllegalArgumentException("Diese Karte sammelt Stempel, keine Punkte");
        }
        return card;
    }

    private CustomerCard ladeKundenkarte(QrPayload qr, Card card) {
        return customerCardRepo.findByCustomer_IdAndCard_Id(qr.customerId(), card.getId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Dieser Kunde hat die Karte noch nicht"));
    }

    private void scanLogSchreiben(Shop shop, Card card, CustomerCard cc, int rewardsEarned) {
        // Wie beim Stempel-Weg: schlaegt das Protokoll fehl, darf die Buchung
        // NICHT scheitern. stampsAdded bleibt 0, damit sich Stempel- und
        // Punktzahlen in der Statistik nicht vermischen.
        try {
            scanLogRepo.save(ScanLog.create(shop.getId(), card.getId(),
                    cc.getCustomer().getId(), 0, rewardsEarned));
        } catch (Exception e) {
            log.warn("ScanLog konnte nicht gespeichert werden: {}", e.getMessage());
        }
    }
}
