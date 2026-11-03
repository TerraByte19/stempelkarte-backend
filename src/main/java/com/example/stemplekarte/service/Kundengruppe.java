package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * In welcher Gruppe eine Kundenkarte steht - automatisch, aus Besuchen und
 * letztem Besuch. Jede Karte genau eine Gruppe, die erste passende Regel
 * gewinnt (Reihenfolge in {@link #von(CustomerCard, int, Regeln, Instant)}).
 *
 * Ruhig und Verloren gelten auch fuer Stammgaeste: genau das will der
 * Laden sehen. Stammgast vor Neu: wer in zehn Tagen sechsmal da war, ist
 * Stammgast, nicht "neu".
 */
public enum Kundengruppe {
    NEU, STAMMGAST, GELEGENTLICH, WIRD_RUHIG, VERLOREN;

    /** Wie Zielgruppe.NEU. */
    static final int NEU_TAGE = 14;

    /** Die drei Stellschrauben des Ladens. */
    public record Regeln(int stammAb, int ruhigTage, int verlorenTage) {
        public static final int STAMM_AB_MIN = 2, STAMM_AB_MAX = 50;
        public static final int RUHIG_MIN = 7, RUHIG_MAX = 180;
        public static final int VERLOREN_MIN = 14, VERLOREN_MAX = 365;

        public static final Regeln STANDARD = new Regeln(
                Shop.GRUPPE_STAMM_AB_STANDARD, Shop.GRUPPE_RUHIG_TAGE_STANDARD, Shop.GRUPPE_VERLOREN_TAGE_STANDARD);

        /** Unbrauchbare Werte (etwa 0 aus einem Mock) = Standard. */
        public static Regeln von(Shop shop) {
            int s = shop.getGruppeStammAb(), r = shop.getGruppeRuhigTage(), v = shop.getGruppeVerlorenTage();
            return s > 0 && r > 0 && v > r ? new Regeln(s, r, v) : STANDARD;
        }

        /** IllegalArgumentException (400) mit Klartext fuers Dashboard. */
        public Regeln pruefe() {
            if (stammAb < STAMM_AB_MIN || stammAb > STAMM_AB_MAX) {
                throw new IllegalArgumentException("Stammgast ab " + STAMM_AB_MIN + " bis " + STAMM_AB_MAX + " Besuchen");
            }
            if (ruhigTage < RUHIG_MIN || ruhigTage > RUHIG_MAX) {
                throw new IllegalArgumentException("Wird ruhig: " + RUHIG_MIN + " bis " + RUHIG_MAX + " Tage");
            }
            if (verlorenTage < VERLOREN_MIN || verlorenTage > VERLOREN_MAX) {
                throw new IllegalArgumentException("Verloren: " + VERLOREN_MIN + " bis " + VERLOREN_MAX + " Tage");
            }
            if (verlorenTage <= ruhigTage) {
                throw new IllegalArgumentException("Verloren muss später kommen als wird ruhig");
            }
            return this;
        }
    }

    /** Leer = keine Einschraenkung (null). Unbekannt = IllegalArgumentException (400). */
    public static Kundengruppe von(String key) {
        if (key == null || key.isBlank()) return null;
        try {
            return valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannte Kundengruppe: " + key);
        }
    }

    /**
     * besuche liefert der Aufrufer: Stempelkarte aus Stempeln und Praemien
     * ({@link #besucheStempelkarte}), Punktekarte aus dem ScanLog.
     */
    public static Kundengruppe von(CustomerCard cc, int besuche, Regeln regeln, Instant jetzt) {
        Instant besuch = cc.letzterBesuch();
        if (besuch != null && besuch.isBefore(jetzt.minus(Duration.ofDays(regeln.verlorenTage())))) return VERLOREN;
        if (besuch != null && besuch.isBefore(jetzt.minus(Duration.ofDays(regeln.ruhigTage())))) return WIRD_RUHIG;
        if (besuche >= regeln.stammAb()) return STAMMGAST;
        if (cc.getCreatedAt() != null && cc.getCreatedAt().isAfter(jetzt.minus(Duration.ofDays(NEU_TAGE)))) return NEU;
        return GELEGENTLICH;
    }

    /**
     * Alle je gesammelten Stempel - dieselbe Formel wie bei der
     * Bewertungs-Erinnerung. Deckt auch die Zeit vor dem ScanLog ab.
     */
    public static int besucheStempelkarte(Card card, CustomerCard cc) {
        return cc.getStamps() + cc.getTotalRewards() * card.getRewardThreshold();
    }
}
