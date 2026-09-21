package com.example.stemplekarte.service;

import com.example.stemplekarte.model.PointsRounding;

/**
 * Umrechnung Einkaufsbetrag -> Punkte. Bewusst ohne Spring und ohne
 * Datenbank, damit jeder Rundungsfall billig festzunageln ist.
 *
 * Alles laeuft in long. Kein double: ein double zeigt nach ein paar
 * Buchungen 5,199999 auf der Wallet-Karte. Kein BigDecimal: fuer eine
 * Multiplikation und eine Division ist das Zeremonie.
 *
 * Einheiten, die durchgaengig gelten:
 * - amountCents        = Betrag in Cent (520 = 5,20 Euro)
 * - pointsPerEuroX100  = Punkte pro Euro, mal 100 (100 = 1 Punkt pro Euro)
 * - Rueckgabe          = Punkte mal 100 (520 = 5,20 Punkte)
 */
public final class PointsMath {

    /** 99 999,99 Euro. Reicht fuer jeden Ladenbon und haelt das Produkt klein. */
    public static final long MAX_AMOUNT_CENTS = 9_999_999L;

    /** 1000 Punkte pro Euro. Darueber wird die Zahl auf der Karte unlesbar. */
    public static final int MAX_POINTS_PER_EURO_X100 = 100_000;

    private PointsMath() {}

    /**
     * Rechnet einen Betrag in Hundertstel-Punkte um.
     *
     * Die Formel ist pointsX100 = amountCents * pointsPerEuroX100 / 100.
     * Gerechnet wird auf dem Betrag OHNE Vorzeichen, das Vorzeichen kommt am
     * Ende zurueck: Javas Division schneidet Richtung Null ab, damit wuerde
     * eine Korrekturbuchung ueber -5,70 Euro kaufmaennisch auf -5 statt -6
     * runden und sich anders verhalten als die Buchung, die sie zuruecknimmt.
     */
    public static long punkteFuer(long amountCents, int pointsPerEuroX100,
                                  PointsRounding rounding) {
        if (Math.abs(amountCents) > MAX_AMOUNT_CENTS) {
            throw new IllegalArgumentException(
                    "Betrag ausserhalb der Grenze (hoechstens 99.999,99 Euro)");
        }
        if (pointsPerEuroX100 < 1 || pointsPerEuroX100 > MAX_POINTS_PER_EURO_X100) {
            throw new IllegalArgumentException(
                    "Kurs ausserhalb der Grenze (1 bis 100.000)");
        }

        long vorzeichen = amountCents < 0 ? -1 : 1;
        long betrag = Math.abs(amountCents);

        // Zaehler traegt zwei Stellen mehr als das Ergebnis: er ist
        // pointsX100 * 100. Auf dieser Zwischenstufe wird gerundet.
        long zaehler = betrag * pointsPerEuroX100;

        long ergebnis = switch (rounding) {
            // Auf das naechste Hundertstel Punkt, ab der Haelfte auf.
            case GENAU -> (zaehler + 50) / 100;
            // Auf ganze Punkte ab, danach wieder in Hundertstel.
            case ABRUNDEN -> (zaehler / 10_000) * 100;
            // Auf ganze Punkte, ab der Haelfte auf.
            case KAUFMAENNISCH -> ((zaehler + 5_000) / 10_000) * 100;
        };

        return vorzeichen * ergebnis;
    }

    /**
     * Punkte fuer die Anzeige: hoechstens zwei Nachkommastellen, nachlaufende
     * Nullen weg. 520 wird "5,2", 2600 wird "26", 104 wird "1,04".
     *
     * Komma statt Punkt, weil die Oberflaechen deutsch und arabisch sind und
     * beide das Komma als Dezimaltrenner setzen.
     */
    public static String formatiere(long pointsX100) {
        long ganz = pointsX100 / 100;
        long rest = Math.abs(pointsX100 % 100);
        if (rest == 0) return String.valueOf(ganz);
        String nachkomma = rest % 10 == 0
                ? String.valueOf(rest / 10)
                : (rest < 10 ? "0" + rest : String.valueOf(rest));
        return ganz + "," + nachkomma;
    }
}
