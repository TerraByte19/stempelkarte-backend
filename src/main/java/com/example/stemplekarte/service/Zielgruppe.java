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
    /** Geburtstag genau heute. Nur fuer den automatischen Gruss (GeburtstagsService), nicht waehlbar. */
    GEBURTSTAG_HEUTE,
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
    FAST_VOLL,
    /**
     * Bewertungs-Erinnerung faellig: Besuch 2 Stunden bis 3 Tage her, genug
     * Besuche (Shop#getBewertungErinnerungAb), noch nie erinnert. Nur fuer
     * den Automaten (BewertungsErinnerungService), nicht waehlbar.
     */
    BEWERTUNG;

    private static final java.time.ZoneId ZONE = java.time.ZoneId.of("Europe/Berlin");
    static final int NEU_TAGE = 14;
    static final int EINMAL_TAGE = 14;
    static final int STAMM_RUHIG_TAGE = 21;
    static final int BEWERTUNG_MIN_STUNDEN = 2;
    static final int BEWERTUNG_MAX_TAGE = 3;

    /** Im Dashboard anbietbar; GEBURTSTAG_HEUTE und BEWERTUNG gehoeren den Automaten. */
    public boolean waehlbar() {
        return this != GEBURTSTAG_HEUTE && this != BEWERTUNG;
    }

    public static List<Zielgruppe> waehlbare() {
        return java.util.Arrays.stream(values()).filter(Zielgruppe::waehlbar).toList();
    }

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
            case GEBURTSTAG_HEUTE -> {
                java.time.LocalDate g = cc.getBirthday();
                java.time.LocalDate heute = jetzt.atZone(ZONE).toLocalDate();
                // 29. Februar: in Nicht-Schaltjahren am 28. gratulieren.
                yield g != null && g.getMonth() == heute.getMonth()
                        && (g.getDayOfMonth() == heute.getDayOfMonth()
                            || (g.getDayOfMonth() == 29 && heute.getDayOfMonth() == 28 && !heute.isLeapYear()));
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
            case BEWERTUNG -> bewertungFaellig(card, cc, jetzt);
        };
    }

    /**
     * Nicht sofort nach dem Stempel (der Push dafuer ist gerade erst
     * gekommen), aber auch nicht Wochen spaeter: wer beim Einschalten lange
     * nicht da war, bekommt die Erinnerung nach seinem naechsten Besuch.
     */
    private static boolean bewertungFaellig(Card card, CustomerCard cc, Instant jetzt) {
        if (cc.getBewertungErinnertAm() != null) return false;
        int ab = card.getShop() != null ? card.getShop().getBewertungErinnerungAb() : 0;
        if (ab <= 0) return false;
        Instant besuch = cc.letzterBesuch();
        if (besuch == null
                || besuch.isAfter(jetzt.minus(Duration.ofHours(BEWERTUNG_MIN_STUNDEN)))
                || besuch.isBefore(jetzt.minus(Duration.ofDays(BEWERTUNG_MAX_TAGE)))) return false;
        // Punktekarten zaehlen keine Besuche: wer schon eine Praemie geholt
        // hat, kommt sicher wieder. Stempelkarten: alle je gesammelten Stempel.
        if (card.isPoints()) return cc.getTotalRewards() >= 1;
        return cc.getStamps() + cc.getTotalRewards() * card.getRewardThreshold() >= ab;
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
