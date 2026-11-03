package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;

/**
 * Stufen einer Stempelkarte (Bronze, Silber, Gold): welche Stufe eine
 * Kundenkarte nach ihren Besuchen hat und wie das auf Karte und Pass
 * steht. Besuche = alle je gesammelten Stempel, wie bei den Kundengruppen.
 */
public final class Stufen {

    private Stufen() {}

    public static final int NAME_MAX = 20;
    public static final int AB_MAX = 1000;

    /** naechsteAb == null: hoechste Stufe. */
    public record Stufe(int index, String name, int ab, String naechsteName, Integer naechsteAb) {
        public boolean hoechste() { return naechsteAb == null; }
    }

    /** null = Stufen aus oder keine Stempelkarte. */
    public static Stufe von(Card card, CustomerCard cc) {
        if (card == null || cc == null || !card.isStufenAktiv()) return null;
        return vonBesuchen(card, besuche(card, cc));
    }

    public static Stufe vonBesuchen(Card card, int besuche) {
        String[] namen = card.getStufenNamen();
        int ab2 = card.getStufe2Ab(), ab3 = card.getStufe3Ab();
        if (besuche >= ab3) return new Stufe(3, namen[2], ab3, null, null);
        if (besuche >= ab2) return new Stufe(2, namen[1], ab2, namen[2], ab3);
        return new Stufe(1, namen[0], 0, namen[1], ab2);
    }

    public static int besuche(Card card, CustomerCard cc) {
        return Kundengruppe.besucheStempelkarte(card, cc);
    }

    /** "Silber · noch 8 Besuche bis Gold" bzw. "Gold · 27 Besuche". */
    public static String text(Stufe s, int besuche) {
        if (s.hoechste()) return s.name() + " · " + besuche + (besuche == 1 ? " Besuch" : " Besuche");
        int fehlen = Math.max(1, s.naechsteAb() - besuche);
        return s.name() + " · noch " + fehlen + (fehlen == 1 ? " Besuch" : " Besuche") + " bis " + s.naechsteName();
    }

    // ── Einstellung ─────────────────────────────────────────────────────

    /** Bereinigte Namen (null = Standard) und geprueft Schwellen. */
    public record Einstellung(String name1, String name2, int ab2, String name3, int ab3) {}

    /** IllegalArgumentException (400) mit Klartext. */
    public static Einstellung pruefe(String name1, String name2, int ab2, String name3, int ab3) {
        if (ab2 < 1 || ab2 > AB_MAX || ab3 < 2 || ab3 > AB_MAX) {
            throw new IllegalArgumentException("Besuche je Stufe zwischen 1 und " + AB_MAX);
        }
        if (ab3 <= ab2) {
            throw new IllegalArgumentException("Stufe 3 muss später kommen als Stufe 2");
        }
        return new Einstellung(name(name1), name(name2), ab2, name(name3), ab3);
    }

    private static String name(String roh) {
        if (roh == null) return null;
        String s = roh.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").trim();
        if (s.length() > NAME_MAX) {
            throw new IllegalArgumentException("Ein Stufenname darf höchstens " + NAME_MAX + " Zeichen haben");
        }
        return s.isEmpty() ? null : s;
    }
}
