package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.Tarif;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sperre der Pro-Funktionen fuer Basic.
 *
 * Gesperrt wird nur, was der Laden NEU einschaltet oder verschickt.
 * Ausschalten, Loeschen und Ansehen geht immer, damit ein Laden nach dem
 * Wechsel auf Basic aufraeumen kann. Die Laeufer (Auto-Nachrichten,
 * Automationen, Geburtstag, geplante Kampagnen) ueberspringen Basic-Laeden;
 * nach einem Wechsel zurueck auf Pro laufen die Einstellungen weiter.
 */
public final class TarifPruefung {

    private TarifPruefung() {}

    /** Wird zu 402 mit code TARIF (nicht 403, darauf loggt das Frontend aus). */
    public static class Gesperrt extends RuntimeException {
        private final Tarif.Funktion funktion;

        public Gesperrt(Tarif.Funktion funktion) {
            super("Das gibt es ab dem Tarif Pro. Schreib uns, wenn du wechseln möchtest.");
            this.funktion = funktion;
        }

        public Tarif.Funktion getFunktion() { return funktion; }
    }

    /**
     * Gratis-Testphase abgelaufen: Scannen (Stempel, Punkte sammeln,
     * Aufladen, Willkommensgeschenk) und alle Nachrichten sind gesperrt, bis
     * der Admin verlaengert oder bezahlt markiert. Login, Dashboard und
     * Kundenliste gehen weiter, Praemien einloesen auch (Kunden sollen nicht
     * leer ausgehen). Die Wallet-Karten bleiben, geloescht wird nichts.
     * 402 mit code TEST_VORBEI.
     */
    public static class TestVorbei extends RuntimeException {
        public TestVorbei() { super(TEST_VORBEI_TEXT); }
    }

    public static final String TEST_VORBEI_TEXT =
            "Die Gratis-Testphase ist vorbei. Bitte melde dich bei Stampit, dann geht es sofort weiter.";

    /** Null-sicher: ohne Testphase (bezahlt, Bestandsladen) nie vorbei. */
    public static boolean testVorbei(Shop shop, Instant jetzt) {
        Instant bis = shop.getTestBis();
        return bis != null && !jetzt.isBefore(bis);
    }

    public static void pruefeTestphase(Shop shop) {
        if (testVorbei(shop, Instant.now())) throw new TestVorbei();
    }

    /**
     * Ohne Tarif (nur bei Test-Attrappen, echte Laeden haben immer einen) gilt
     * alles als erlaubt. Nach der Testphase ist nichts erlaubt (die Laeufer
     * ueberspringen den Laden).
     */
    public static boolean darf(Shop shop, Tarif.Funktion f) {
        if (testVorbei(shop, Instant.now())) return false;
        Tarif t = shop.getTarif();
        return t == null || t.erlaubt(f);
    }

    public static void pruefe(Shop shop, Tarif.Funktion f) {
        pruefeTestphase(shop);
        if (!darf(shop, f)) throw new Gesperrt(f);
    }

    /**
     * Fuer Dashboard und Profil. grenzeErreicht = Hinweis zeigen; es wird
     * nichts gesperrt, neue Kunden melden sich weiter an.
     */
    /** funktionen: nur nach Tarif (nicht nach Testphase); testVorbei extra. */
    public record Stand(String tarif, Integer kundenGrenze, long kunden, boolean grenzeErreicht,
                        Map<String, Boolean> funktionen, boolean testVorbei) {}

    public static Stand stand(Shop shop, long kunden) {
        Tarif t = shop.getTarif() != null ? shop.getTarif() : Tarif.STANDARD;
        Integer grenze = t.kundenGrenze();
        Map<String, Boolean> funktionen = new LinkedHashMap<>();
        for (Tarif.Funktion f : Tarif.Funktion.values()) {
            funktionen.put(f.name().toLowerCase(Locale.ROOT), t.erlaubt(f));
        }
        return new Stand(t.name(), grenze, kunden, grenze != null && kunden >= grenze, funktionen,
                testVorbei(shop, Instant.now()));
    }
}
