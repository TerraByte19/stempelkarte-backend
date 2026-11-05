package com.example.stemplekarte.service;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.wallet.GoogleWalletService;
import com.example.stemplekarte.wallet.WalletNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kunde wirbt Kunde. Jede Kundenkarte traegt einen Code; wer sich ueber
 * den Link eines Freundes fuer DIESELBE Karte anmeldet und zum ersten Mal
 * da ist, bringt beiden den Bonus. Nicht bei der Anmeldung: Boni fuer
 * Karteileichen will kein Laden.
 *
 * Der Push an den Werber geht erst nach dem Commit raus
 * (TransactionSynchronization) - sonst holt sich das iPhone den Pass, bevor
 * der neue Stand in der Datenbank steht.
 */
@Service
public class EmpfehlungService {

    private static final Logger log = LoggerFactory.getLogger(EmpfehlungService.class);

    /** Ohne I, O, 0, 1: am Telefon und auf Papier nicht zu verwechseln. */
    static final String ZEICHEN = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LAENGE = 6;

    private final CustomerCardRepository customerCardRepo;
    private final AppProperties props;
    private final WalletNotifier notifier;
    private final SecureRandom zufall = new SecureRandom();

    public EmpfehlungService(CustomerCardRepository customerCardRepo, AppProperties props, WalletNotifier notifier) {
        this.customerCardRepo = customerCardRepo;
        this.props = props;
        this.notifier = notifier;
    }

    // ── Code und Link ───────────────────────────────────────────────────

    String neuerCode() {
        for (int versuch = 0; versuch < 20; versuch++) {
            StringBuilder sb = new StringBuilder(CODE_LAENGE);
            for (int i = 0; i < CODE_LAENGE; i++) sb.append(ZEICHEN.charAt(zufall.nextInt(ZEICHEN.length())));
            String code = sb.toString();
            if (!customerCardRepo.existsByEmpfehlungsCode(code)) return code;
        }
        throw new IllegalStateException("Kein freier Empfehlungscode gefunden");
    }

    /**
     * Code vergeben, falls die Karte noch keinen hat (alte Karten). Speichert
     * nur dann. Bewusst void: ein Mock gaebe sonst null zurueck, und der
     * Aufrufer arbeitet mit seiner Karte weiter.
     */
    public void codeSicherstellen(CustomerCard cc) {
        if (cc.getEmpfehlungsCode() != null) return;
        cc.empfehlungsCodeSetzen(neuerCode());
        customerCardRepo.save(cc);
    }

    /** Fuer neue Karten, bevor sie gespeichert werden. */
    public void codeVergeben(CustomerCard cc) {
        if (cc.getEmpfehlungsCode() == null) cc.empfehlungsCodeSetzen(neuerCode());
    }

    public String link(CustomerCard cc) {
        return linkFuer(props.baseUrl(), cc.getCard().getId(), cc.getEmpfehlungsCode());
    }

    /** Auch fuer Pass und Google-Objekt, die ihre eigene baseUrl tragen. */
    public static String linkFuer(String baseUrl, String cardId, String code) {
        return baseUrl + "/karte-neu/" + cardId + "?ref=" + code;
    }

    /** Ein Satz fuer Karte, Pass und Anmeldeseite. */
    public static String werbeText(Card card) {
        return werbeText(com.example.stemplekarte.i18n.Texts.STANDARD, card);
    }

    public static String werbeText(String lang, Card card) {
        int bonus = card.getEmpfehlungBonus();
        String einheit = com.example.stemplekarte.i18n.Texts.t(lang, card.isPoints()
                ? (bonus == 1 ? "unit.point_one" : "unit.points")
                : (bonus == 1 ? "unit.stamp_one" : "unit.stamps"));
        return com.example.stemplekarte.i18n.Texts.t(lang, "referral.text", bonus, einheit);
    }

    /** Zeigt die Karte Code und Link? Nur wenn der Laden es an hat und die Karte einen Code traegt. Mitglieds- und 10er-Karten nie. */
    public static boolean sichtbar(Card card, CustomerCard cc) {
        return card != null && card.isEmpfehlungAktiv() && (card.isStamp() || card.isPoints())
                && cc != null && cc.getEmpfehlungsCode() != null;
    }

    static String codeSauber(String roh) {
        if (roh == null) return null;
        String s = roh.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return s.isEmpty() ? null : s;
    }

    // ── Verknuepfen bei der Anmeldung ───────────────────────────────────

    /**
     * Nur fuer eine NEUE Kundenkarte. Ein unbekannter oder unpassender Code
     * wird still ignoriert: die Anmeldung darf daran nie scheitern.
     * Speichert nicht - der Aufrufer speichert die Karte ohnehin.
     */
    public boolean verknuepfen(CustomerCard neu, String refCode, Instant jetzt) {
        String code = codeSauber(refCode);
        if (code == null || neu.getGeworbenVon() != null) return false;
        Card card = neu.getCard();
        if (card == null || !card.isEmpfehlungAktiv()) return false;
        CustomerCard werber = customerCardRepo.findByEmpfehlungsCode(code).orElse(null);
        if (werber == null) {
            log.info("[EMPFEHLUNG] Code {} unbekannt, Karte {}", code, card.getId());
            return false;
        }
        if (werber.getCard() == null || !card.getId().equals(werber.getCard().getId())) return false;
        if (neu.getCustomer() != null && werber.getCustomer() != null
                && neu.getCustomer().getId() != null
                && neu.getCustomer().getId().equals(werber.getCustomer().getId())) return false;
        neu.geworbenVon(werber.getId(), jetzt);
        log.info("[EMPFEHLUNG] Karte {} geworben von {}", neu.getId(), werber.getId());
        return true;
    }

    // ── Bonus beim ersten Besuch ────────────────────────────────────────

    /** bonus = was beide bekommen haben; punkte = Einheit. */
    public record Bonus(int bonus, boolean punkte) {}

    /**
     * Nach dem ersten echten Besuch des Geworbenen. Einmal pro Karte. Ist
     * die Empfehlung inzwischen aus oder der Werber weg, wird nur der
     * Merker gesetzt. Der Aufrufer speichert die geworbene Karte selbst;
     * der Werber wird hier gespeichert und nach dem Commit benachrichtigt.
     */
    public Bonus belohnen(CustomerCard geworben, Instant jetzt) {
        if (geworben.getGeworbenVon() == null || geworben.getEmpfehlungBonusAm() != null) return null;
        geworben.empfehlungBelohnt(jetzt);
        Card card = geworben.getCard();
        if (card == null || !card.isEmpfehlungAktiv() || !(card.isStamp() || card.isPoints())) return null;
        CustomerCard werber = customerCardRepo.findById(geworben.getGeworbenVon()).orElse(null);
        if (werber == null) return null;

        int bonus = card.getEmpfehlungBonus();
        boolean punkte = card.isPoints();
        if (punkte) {
            geworben.bonusPunkte(bonus * 100L);
            werber.bonusPunkte(bonus * 100L);
        } else {
            geworben.bonusStempel(bonus, card.getRewardThreshold());
            werber.bonusStempel(bonus, card.getRewardThreshold());
        }
        customerCardRepo.save(werber);
        log.info("[EMPFEHLUNG] Bonus {} {} fuer {} (geworben) und {} (Werber)",
                bonus, punkte ? "Punkte" : "Stempel", geworben.getId(), werber.getId());
        nachCommit(() -> notifier.nachStempelAenderung(werber,
                punkte ? GoogleWalletService.Meldung.KEINE : GoogleWalletService.Meldung.STEMPEL));
        return new Bonus(bonus, punkte);
    }

    /** Im Test (keine Transaktion) sofort. */
    private static void nachCommit(Runnable arbeit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { arbeit.run(); }
            });
        } else {
            arbeit.run();
        }
    }

    // ── Einstellung und Zahlen fuers Dashboard ──────────────────────────

    public record Werber(String name, long anzahl) {}
    public record Stand(boolean aktiv, int bonus, long geworben, long belohnt, List<Werber> top) {}

    public Stand stand(Card card) {
        List<Werber> top = new ArrayList<>();
        for (Object[] zeile : customerCardRepo.topWerber(card, PageRequest.of(0, 5))) {
            String werberId = String.valueOf(zeile[0]);
            long anzahl = ((Number) zeile[1]).longValue();
            String name = customerCardRepo.findById(werberId)
                    .map(w -> w.getCustomer() != null ? w.getCustomer().getName() : null)
                    .orElse(null);
            top.add(new Werber(name != null ? name : "Gelöschter Kunde", anzahl));
        }
        return new Stand(card.isEmpfehlungAktiv(), card.getEmpfehlungBonus(),
                customerCardRepo.countByCardAndGeworbenVonIsNotNull(card),
                customerCardRepo.countByCardAndEmpfehlungBonusAmIsNotNull(card), top);
    }

    /** Bereich pruefen; speichert nicht (der Controller speichert die Karte). */
    public void einstellen(Card card, boolean aktiv, int bonus) {
        if (!(card.isStamp() || card.isPoints())) {
            throw new IllegalArgumentException("Kunde wirbt Kunde gibt es nur für Stempel- und Punktekarten");
        }
        if (bonus < 1 || bonus > Card.EMPFEHLUNG_BONUS_MAX) {
            throw new IllegalArgumentException("Bonus zwischen 1 und " + Card.EMPFEHLUNG_BONUS_MAX);
        }
        if (!card.isPoints() && bonus >= card.getRewardThreshold()) {
            throw new IllegalArgumentException("Der Bonus muss kleiner sein als die Stempel bis zur Prämie");
        }
        card.updateEmpfehlung(aktiv, bonus);
    }
}
