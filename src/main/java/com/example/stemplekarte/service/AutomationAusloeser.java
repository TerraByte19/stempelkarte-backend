package com.example.stemplekarte.service;

import java.util.Locale;

/**
 * Ausloeser einer eigenen Automation (model.Automation). wert ist je nach
 * Ausloeser eine Zahl Tage oder eine Anzahl; min/max begrenzen ihn.
 *
 * Wann eine Karte "dran" ist, steht in AutomationService#passt - hier nur,
 * was es gibt und was die Oberflaeche dazu sagt.
 */
public enum AutomationAusloeser {
    /** n Tage nach der Anmeldung (0 = am Tag danach). Einmal je Karte. */
    NACH_ANMELDUNG("Tage nach der Anmeldung", "Tage", 0, 365),
    /** Seit n Tagen kein Besuch. Einmal je Pause. */
    KEIN_BESUCH_SEIT("Tage ohne Besuch", "Tage", 1, 365),
    /** Am Tag nach dem n-ten Besuch. Einmal je Karte. Nicht fuer Punktekarten. */
    BESUCH_NR("beim n-ten Besuch", "Besuche", 1, 500),
    /** Am Tag nach dem Stempel, mit dem der Stand n erreicht wurde. Einmal je Runde. Nur Stempelkarten. */
    STEMPEL_STAND("bei n Stempeln", "Stempel", 1, 100),
    /** n Tage vor dem Geburtstag (0 = am Geburtstag). Einmal im Jahr. */
    GEBURTSTAG_IN_TAGEN("Tage vor dem Geburtstag", "Tage", 0, 60),
    /** 10er-Karte: am Tag nach dem Besuch, nach dem noch n uebrig sind. Einmal je Paket. */
    PAKET_REST("10er-Karte: noch n übrig", "übrig", 0, 100);

    private final String titel;
    private final String einheit;
    private final int min;
    private final int max;

    AutomationAusloeser(String titel, String einheit, int min, int max) {
        this.titel = titel;
        this.einheit = einheit;
        this.min = min;
        this.max = max;
    }

    public String titel() { return titel; }
    public String einheit() { return einheit; }
    public int min() { return min; }
    public int max() { return max; }

    /** Unbekannt = IllegalArgumentException (400). */
    public static AutomationAusloeser von(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Auslöser fehlt");
        try {
            return valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannter Auslöser: " + key);
        }
    }

    /** Bereich pruefen; null = Minimum. */
    public int pruefeWert(Integer wert) {
        int w = wert != null ? wert : min;
        if (w < min || w > max) {
            throw new IllegalArgumentException(titel + ": Wert zwischen " + min + " und " + max);
        }
        return w;
    }
}
