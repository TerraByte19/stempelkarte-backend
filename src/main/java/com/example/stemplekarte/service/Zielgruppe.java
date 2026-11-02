package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Reward;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Wer eine Kampagne bekommt. Die Regeln laufen in Java ueber die Grundmenge
 * (CustomerCardRepository#findKampagnenBasis), nicht in JPQL: "Praemie
 * wartet" braucht bei Punktekarten den Katalog, und die Besuchsregel haengt
 * an letzterBesuch(), das schon zwei Spalten zusammenfasst.
 *
 * Kein Geburtstag: den fragen wir bei der Anmeldung nicht ab.
 */
public enum Zielgruppe {
    /** Jede aktive Karte des Ladens. */
    ALLE,
    /** Karte in den letzten 14 Tagen angelegt. */
    NEU,
    /** Geburtstag im laufenden Monat. Nur, wenn der Laden ihn bei der Anmeldung abfragt. */
    GEBURTSTAG,
    INAKTIV_30,
    INAKTIV_60,
    INAKTIV_90,
    /** Genau ein Stempel, seit 14 Tagen nicht mehr da. Nur Stempelkarten. */
    EINMAL,
    /** Schon einmal eine Praemie geholt, seit 21 Tagen nicht da. */
    STAMM_RUHIG,
    /** Karte voll beziehungsweise genug Punkte fuer eine Praemie. */
    PRAEMIE_WARTET,
    /** Noch 1 oder 2 Stempel bis zur Praemie. Nur Stempelkarten. */
    FAST_VOLL;

    private static final java.time.ZoneId ZONE = java.time.ZoneId.of("Europe/Berlin");
    static final int NEU_TAGE = 14;
    static final int EINMAL_TAGE = 14;
    static final int STAMM_RUHIG_TAGE = 21;

    /** Leer = ALLE. Unbekannt = IllegalArgumentException (400). */
    public static Zielgruppe von(String key) {
        if (key == null || key.isBlank()) return ALLE;
        try {
            return valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannte Zielgruppe: " + key);
        }
    }

    /**
     * jetzt kommt von aussen, damit der Test eine Pause simulieren kann,
     * ohne an lastVisitAt zu drehen. katalog liefert die aktiven Praemien
     * einer Punktekarte - der Aufrufer darf das zwischenspeichern.
     */
    public boolean passt(CustomerCard cc, Instant jetzt, Function<Card, List<Reward>> katalog) {
        Card card = cc.getCard();
        return switch (this) {
            case ALLE -> true;
            case NEU -> cc.getCreatedAt() != null
                    && cc.getCreatedAt().isAfter(jetzt.minus(Duration.ofDays(NEU_TAGE)));
            case GEBURTSTAG -> {
                // Von der Karte, nicht vom Kunden: nur, was er DIESEM Laden gesagt hat.
                java.time.LocalDate g = cc.getBirthday();
                yield g != null && g.getMonth() == jetzt.atZone(ZONE).getMonth();
            }
            case INAKTIV_30 -> pauseSeit(cc, jetzt, 30);
            case INAKTIV_60 -> pauseSeit(cc, jetzt, 60);
            case INAKTIV_90 -> pauseSeit(cc, jetzt, 90);
            case EINMAL -> !card.isPoints() && cc.getTotalRewards() == 0 && cc.getStamps() == 1
                    && pauseSeit(cc, jetzt, EINMAL_TAGE);
            case STAMM_RUHIG -> cc.getTotalRewards() >= 1 && pauseSeit(cc, jetzt, STAMM_RUHIG_TAGE);
            case PRAEMIE_WARTET -> praemieWartet(card, cc, katalog);
            case FAST_VOLL -> {
                if (card.isPoints()) yield false;
                int offen = card.getRewardThreshold() - cc.getStamps();
                yield offen >= 1 && offen <= 2;
            }
        };
    }

    private static boolean pauseSeit(CustomerCard cc, Instant jetzt, int tage) {
        Instant besuch = cc.letzterBesuch();
        return besuch != null && besuch.isBefore(jetzt.minus(Duration.ofDays(tage)));
    }

    private static boolean praemieWartet(Card card, CustomerCard cc, Function<Card, List<Reward>> katalog) {
        if (!card.isPoints()) return cc.getStamps() >= card.getRewardThreshold();
        Reward ziel = RewardService.naechstesZiel(katalog.apply(card), cc.getPointsX100());
        return ziel != null && cc.getPointsX100() >= ziel.getCostPointsX100();
    }
}
