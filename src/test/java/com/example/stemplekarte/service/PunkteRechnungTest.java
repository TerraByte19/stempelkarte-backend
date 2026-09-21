package com.example.stemplekarte.service;

import com.example.stemplekarte.model.PointsRounding;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nagelt die Umrechnung Euro -> Punkte fest.
 *
 * Der Kurs liegt als EINE Ganzzahl vor (Punkte pro Euro, mal 100), damit
 * "5 Punkte pro Euro" und "1 Punkt pro 5 Euro" dasselbe Feld benutzen und
 * kein double ins Spiel kommt. Ein double zeigt sonst irgendwann 5,199999
 * auf der Wallet-Karte.
 *
 * Alle Ergebnisse sind Hundertstel-Punkte: 520 bedeutet 5,20 Punkte.
 */
class PunkteRechnungTest {

    @Test
    void einEuroEinPunkt_genau() {
        // 5,20 Euro bei 1 Euro = 1 Punkt
        assertThat(PointsMath.punkteFuer(520, 100, PointsRounding.GENAU)).isEqualTo(520);
    }

    @Test
    void einEuroFuenfPunkte_genau() {
        assertThat(PointsMath.punkteFuer(520, 500, PointsRounding.GENAU)).isEqualTo(2600);
    }

    @Test
    void fuenfEuroEinPunkt_genau() {
        assertThat(PointsMath.punkteFuer(520, 20, PointsRounding.GENAU)).isEqualTo(104);
    }

    @Test
    void zweiEuroDreiPunkte_genau() {
        assertThat(PointsMath.punkteFuer(520, 150, PointsRounding.GENAU)).isEqualTo(780);
    }

    @Test
    void abrunden_schneidetAufGanzePunkte() {
        // 5,70 Punkte -> 5
        assertThat(PointsMath.punkteFuer(570, 100, PointsRounding.ABRUNDEN)).isEqualTo(500);
    }

    @Test
    void kaufmaennisch_rundetAbDerHaelfteAuf() {
        // 5,70 -> 6
        assertThat(PointsMath.punkteFuer(570, 100, PointsRounding.KAUFMAENNISCH)).isEqualTo(600);
        // 5,20 -> 5
        assertThat(PointsMath.punkteFuer(520, 100, PointsRounding.KAUFMAENNISCH)).isEqualTo(500);
        // genau 5,50 -> 6 (ab der Haelfte auf)
        assertThat(PointsMath.punkteFuer(550, 100, PointsRounding.KAUFMAENNISCH)).isEqualTo(600);
    }

    @Test
    void negativerBetrag_rundetSymmetrisch() {
        // Korrekturbuchung: -5,70 Euro darf nicht anders runden als +5,70.
        // Javas Division schneidet Richtung Null ab - ohne Vorzeichen-
        // behandlung waere -5,70 kaufmaennisch faelschlich -5 statt -6.
        assertThat(PointsMath.punkteFuer(-570, 100, PointsRounding.KAUFMAENNISCH)).isEqualTo(-600);
        assertThat(PointsMath.punkteFuer(-570, 100, PointsRounding.ABRUNDEN)).isEqualTo(-500);
    }

    @Test
    void grossterErlaubterFall_laeuftNichtUeber() {
        long ergebnis = PointsMath.punkteFuer(
                PointsMath.MAX_AMOUNT_CENTS, PointsMath.MAX_POINTS_PER_EURO_X100,
                PointsRounding.GENAU);
        // 99 999,99 Euro mal 1000 Punkte pro Euro = 99 999 990 Punkte
        assertThat(ergebnis).isEqualTo(9_999_999_000L);
        assertThat(ergebnis).isPositive();
    }

    @Test
    void betragUeberGrenze_wirdAbgelehnt() {
        assertThatThrownBy(() -> PointsMath.punkteFuer(
                PointsMath.MAX_AMOUNT_CENTS + 1, 100, PointsRounding.GENAU))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Betrag");
    }

    @Test
    void kursUeberGrenze_wirdAbgelehnt() {
        assertThatThrownBy(() -> PointsMath.punkteFuer(
                100, PointsMath.MAX_POINTS_PER_EURO_X100 + 1, PointsRounding.GENAU))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kurs");
    }

    @Test
    void anzeige_schneidetNachlaufendeNullenAb() {
        assertThat(PointsMath.formatiere(520)).isEqualTo("5,2");
        assertThat(PointsMath.formatiere(2600)).isEqualTo("26");
        assertThat(PointsMath.formatiere(104)).isEqualTo("1,04");
        assertThat(PointsMath.formatiere(0)).isEqualTo("0");
    }
}
