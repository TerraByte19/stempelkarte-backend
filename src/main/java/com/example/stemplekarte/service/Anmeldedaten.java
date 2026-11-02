package com.example.stemplekarte.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Pruefung der optionalen Anmeldefelder. Was hier durchkommt, landet in der
 * Datenbank und in der Kundenliste - deshalb Laenge und Zeichen eng halten.
 * Leer = nicht angegeben (null), kein Fehler; ob das Feld Pflicht ist,
 * entscheidet der Aufrufer anhand der Ladeneinstellung.
 */
public final class Anmeldedaten {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final int TELEFON_MAX = 30;

    private Anmeldedaten() {}

    /** ISO-Datum (JJJJ-MM-TT) aus dem date-Feld. Nicht in der Zukunft, nicht aelter als 120 Jahre. */
    public static LocalDate geburtstag(String roh) {
        if (roh == null || roh.isBlank()) return null;
        LocalDate d;
        try {
            d = LocalDate.parse(roh.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Ungültiges Geburtsdatum");
        }
        LocalDate heute = LocalDate.now(ZONE);
        if (d.isAfter(heute) || d.isBefore(heute.minusYears(120))) {
            throw new IllegalArgumentException("Ungültiges Geburtsdatum");
        }
        return d;
    }

    /** Ziffern, +, Leerzeichen, Klammern, Schraegstrich, Punkt, Bindestrich. */
    public static String telefon(String roh) {
        if (roh == null) return null;
        String t = roh.trim();
        if (t.isEmpty()) return null;
        if (t.length() > TELEFON_MAX || !t.matches("[+0-9 ()/.\\-]+") || !t.matches(".*[0-9].*")) {
            throw new IllegalArgumentException("Ungültige Handynummer");
        }
        return t;
    }
}
