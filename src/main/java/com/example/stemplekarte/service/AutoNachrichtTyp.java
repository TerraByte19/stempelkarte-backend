package com.example.stemplekarte.service;

import com.example.stemplekarte.model.CustomerCard;

import java.time.Instant;
import java.util.Locale;

/**
 * Die fuenf Automaten: welche Zielgruppe sie treffen, was sie ohne eigenen
 * Text sagen, wie oft sie duerfen und welchen Merker sie setzen.
 *
 * proPerson: der Merker geht auf alle Karten der Person bei diesem Laden
 * (Willkommen soll nicht auf der zweiten Karte noch einmal kommen). Die
 * Runden-Merker (fast voll, Praemie bereit) gehoeren zur einzelnen Karte.
 */
public enum AutoNachrichtTyp {
    WILLKOMMEN(Zielgruppe.AUTO_WILLKOMMEN,
            "Schön, dass du da warst! Sammle weiter, dann gibt's bald: {praemie}.", false, true),
    ZWEITER_BESUCH(Zielgruppe.AUTO_ZWEITER_BESUCH,
            "Danke fürs Wiederkommen! Noch {offen} Stempel bis: {praemie}.", false, true),
    FAST_VOLL(Zielgruppe.AUTO_FAST_VOLL,
            "Fast geschafft! Nur noch {offen} Stempel bis: {praemie}. Wir freuen uns auf dich!", false, false),
    PRAEMIE_BEREIT(Zielgruppe.AUTO_PRAEMIE,
            "Deine Prämie wartet: {praemie}. Hol sie dir beim nächsten Besuch ab!", false, false),
    VIP(Zielgruppe.AUTO_VIP,
            "Du gehörst zu unseren besten Kunden. Danke für deine Treue!", true, false),
    STUFE(Zielgruppe.AUTO_STUFE,
            "Glückwunsch, {name}! Du bist jetzt {stufe} bei uns.", false, false),
    /** 10er-Karte: Paket aufgebraucht, ein paar Tage nach dem letzten Besuch. */
    PAKET_LEER(Zielgruppe.AUTO_PAKET_LEER,
            "Dein Paket ist aufgebraucht. Hol dir beim nächsten Besuch ein neues – wir freuen uns auf dich!", false, false);

    private final Zielgruppe gruppe;
    private final String standardText;
    private final boolean monatlich;
    private final boolean proPerson;

    AutoNachrichtTyp(Zielgruppe gruppe, String standardText, boolean monatlich, boolean proPerson) {
        this.gruppe = gruppe;
        this.standardText = standardText;
        this.monatlich = monatlich;
        this.proPerson = proPerson;
    }

    public Zielgruppe gruppe() { return gruppe; }
    public String standardText() { return standardText; }
    /** Einmal im Monat pro Laden statt einmal am Tag. */
    public boolean monatlich() { return monatlich; }
    public boolean proPerson() { return proPerson; }

    /** Unbekannt = IllegalArgumentException (400). */
    public static AutoNachrichtTyp von(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Typ fehlt");
        try {
            return valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannter Automat: " + key);
        }
    }

    /** Merker nach dem Versand. VIP hat keinen. */
    public void markiere(CustomerCard cc, Instant jetzt) {
        switch (this) {
            case WILLKOMMEN -> cc.willkommenGeschickt(jetzt);
            case ZWEITER_BESUCH -> cc.zweiterBesuchGeschickt(jetzt);
            case FAST_VOLL -> cc.fastVollGeschickt();
            case PRAEMIE_BEREIT -> cc.praemieBereitGeschickt();
            case VIP -> { }
            case STUFE -> {
                Stufen.Stufe s = Stufen.von(cc.getCard(), cc);
                if (s != null) cc.stufeGemeldet(s.index());
            }
            case PAKET_LEER -> cc.paketLeerGeschickt();
        }
    }

    /** Schon markiert? Fuer proPerson: andere Karten nicht doppelt anfassen. */
    public boolean markiert(CustomerCard cc) {
        return switch (this) {
            case WILLKOMMEN -> cc.getAutoWillkommenAm() != null;
            case ZWEITER_BESUCH -> cc.getAutoZweiterAm() != null;
            case FAST_VOLL -> cc.getAutoFastVollBei() != null && cc.getAutoFastVollBei() == cc.getTotalRewards();
            case PRAEMIE_BEREIT -> cc.getAutoPraemieBei() != null && cc.getAutoPraemieBei() == cc.getTotalRewards();
            case VIP -> false;
            case STUFE -> {
                Stufen.Stufe s = Stufen.von(cc.getCard(), cc);
                yield s == null || (cc.getStufeBenachrichtigt() != null && cc.getStufeBenachrichtigt() >= s.index());
            }
            case PAKET_LEER -> cc.getAutoPaketLeerBei() != null && cc.getAutoPaketLeerBei() == cc.getPaketeGekauft();
        };
    }
}
