package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;

import java.time.Duration;
import java.time.Instant;

/**
 * Ohne hinterlegten Ladenort darf nicht gescannt werden.
 *
 * <p>Der Ort ist die Grundlage der Geo-Pruefung: fehlt er, laeuft jeder
 * Scan ungeprueft durch, und ein weitergegebener Personal-Token faellt
 * nie auf. Deshalb Pflicht - aber nur fuers Scannen. Karten anlegen,
 * Design aendern und Statistiken bleiben offen; wer gerade einrichtet,
 * steht oft nicht im Laden und koennte den Ort gar nicht setzen.
 *
 * <p>Zwei Gruppen:
 * <ul>
 *   <li>Laeden, die es vor der Umstellung schon gab, haben eine Frist.
 *       Ihnen mitten im Betrieb das Stempeln abzuschalten waere ein
 *       Ausfall, den sie nicht verschuldet haben.</li>
 *   <li>Neue Laeden brauchen den Ort sofort. Sie richten gerade ein,
 *       niemand steht an der Kasse und wartet.</li>
 * </ul>
 */
public final class StandortPflicht {

    private StandortPflicht() {}

    /** Stichtag der Umstellung. Wer danach angelegt wird, gilt als neu. */
    public static final Instant PFLICHT_AB = Instant.parse("2026-10-06T00:00:00Z");

    /** So lange duerfen bestehende Laeden noch ohne Ort scannen. */
    public static final Duration FRIST = Duration.ofDays(14);

    public static Instant fristEnde() {
        return PFLICHT_AB.plus(FRIST);
    }

    public static boolean hatStandort(Shop shop) {
        return shop != null && shop.getLatitude() != null && shop.getLongitude() != null;
    }

    /** Neu heisst: angelegt ab dem Stichtag. */
    public static boolean istNeu(Shop shop) {
        Instant angelegt = shop.getCreatedAt();
        // Ohne Datum lieber als alt behandeln: ein fehlendes Feld darf
        // keinen bestehenden Laden ohne Vorwarnung abschalten.
        return angelegt != null && !angelegt.isBefore(PFLICHT_AB);
    }

    public static boolean darfScannen(Shop shop, Instant jetzt) {
        if (hatStandort(shop)) return true;
        if (istNeu(shop)) return false;
        return jetzt.isBefore(fristEnde());
    }

    /** Text fuer das Personal an der Kasse - es kann selbst nichts tun. */
    public static String sperrText() {
        return "Scannen gesperrt: Für diesen Laden ist noch kein Standort hinterlegt. "
                + "Der Inhaber kann ihn im Profil festlegen.";
    }
}
