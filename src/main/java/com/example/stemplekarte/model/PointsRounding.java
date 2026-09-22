package com.example.stemplekarte.model;

/**
 * Wie das Rechenergebnis gerundet wird. Entscheidet der Laden, nicht der
 * Entwickler: ein Kiosk will krumme Punkte, eine Baeckerei lieber ganze.
 */
public enum PointsRounding {
    /** Zwei Nachkommastellen, nichts verfaellt. 5,20 Euro -> 5,2 Punkte. */
    GENAU,
    /** Auf ganze Punkte ab. 5,70 Euro -> 5 Punkte. */
    ABRUNDEN,
    /** Auf ganze Punkte, ab der Haelfte auf. 5,70 Euro -> 6 Punkte. */
    KAUFMAENNISCH
}
