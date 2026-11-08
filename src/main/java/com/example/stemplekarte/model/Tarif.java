package com.example.stemplekarte.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Tarif eines Ladens, vom Admin gesetzt (Shop.tarif).
 *
 * Basic: ein Standort, bis 200 Kunden (an der Grenze nur ein Hinweis,
 * niemand wird abgewiesen), ohne die Funktionen in {@link Funktion}.
 * Pro und Kette: alles. Wie viele Filialen Pro und Kette haben, setzt der
 * Admin weiter ueber Shop.maxOrte, weil weitere Filialen extra kosten.
 *
 * Laeden ohne Eintrag gelten als Pro: Bestandslaeden behalten alles, und
 * neue Laeden testen in den 14 Gratis-Tagen den vollen Umfang.
 */
public enum Tarif {
    BASIC, PRO, KETTE;

    /** Ohne Eintrag in der Datenbank. */
    public static final Tarif STANDARD = PRO;

    /** Ab so vielen Kunden bekommt ein Basic-Laden den Hinweis. */
    public static final int BASIC_KUNDEN = 200;   // User 2026-11-08: 200 statt 100 (mit Basic 29 EUR)

    /** Was Basic nicht hat. Alles andere hat jeder Tarif. */
    public enum Funktion {
        KAMPAGNEN, AUTO_NACHRICHTEN, AUTOMATIONEN, TAGESANGEBOT, NEWSLETTER, API, FILIALEN
    }

    private static final Set<Funktion> OHNE_BASIC = EnumSet.allOf(Funktion.class);

    public boolean erlaubt(Funktion f) {
        return this != BASIC || !OHNE_BASIC.contains(f);
    }

    /** Kundenzahl, ab der der Hinweis kommt; null = unbegrenzt. */
    public Integer kundenGrenze() {
        return this == BASIC ? BASIC_KUNDEN : null;
    }

    /** Aus dem Admin-Panel. Unbekannt -> IllegalArgumentException (400). */
    public static Tarif von(String roh) {
        if (roh == null || roh.isBlank()) throw new IllegalArgumentException("Bitte einen Tarif wählen");
        try {
            return valueOf(roh.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannter Tarif: " + roh.trim());
        }
    }
}
