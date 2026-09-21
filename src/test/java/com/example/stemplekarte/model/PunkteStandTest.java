package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Punktestand liegt in Hundertsteln, damit 5,20 Euro bei 1 Euro =
 * 1 Punkt sauber 5,2 Punkte ergeben, ohne dass ein double ins Spiel kommt.
 *
 * Der wichtige Fall ist der Abzug unter den Bestand: eine Korrektur darf
 * die Karte nicht ins Minus druecken. Gleiches Muster wie redeemReward bei
 * den Stempeln, das seit jeher mit Math.max(0, ...) arbeitet. addPoints
 * gibt deshalb zurueck, was TATSAECHLICH gebucht wurde - die Buchungszeile
 * soll die Wirklichkeit festhalten, nicht die Absicht.
 */
class PunkteStandTest {

    @Test
    void frischeKarte_hatNullPunkte() {
        CustomerCard cc = CustomerCard.create(null, null);
        assertThat(cc.getPointsX100()).isZero();
    }

    @Test
    void punkteKommenDazu() {
        CustomerCard cc = CustomerCard.create(null, null);
        long gebucht = cc.addPoints(520);
        assertThat(gebucht).isEqualTo(520);
        assertThat(cc.getPointsX100()).isEqualTo(520);
    }

    @Test
    void abzugGehtNichtUnterNull() {
        CustomerCard cc = CustomerCard.create(null, null);
        cc.addPoints(300);
        long gebucht = cc.addPoints(-500);
        // Nur 300 waren da, also wurden auch nur 300 abgezogen.
        assertThat(gebucht).isEqualTo(-300);
        assertThat(cc.getPointsX100()).isZero();
    }

    @Test
    void stempelBleibenUnberuehrt() {
        // Punktebuchungen duerfen den Stempelstand nicht anfassen - sonst
        // waere eine Karte, die beide Spalten traegt, nicht mehr eindeutig.
        CustomerCard cc = CustomerCard.create(null, null);
        cc.addStamp();
        cc.addPoints(1000);
        assertThat(cc.getStamps()).isEqualTo(1);
    }

    @Test
    void bezahlbarkeitPruefen() {
        CustomerCard cc = CustomerCard.create(null, null);
        cc.addPoints(25_000);
        assertThat(cc.kannBezahlen(25_000)).isTrue();   // genau reicht
        assertThat(cc.kannBezahlen(25_001)).isFalse();
    }

    @Test
    void belohnungZaehlenUndZurueckgeben() {
        // Einloesen zaehlt hoch, Zuruecknehmen wieder runter - aber nie
        // unter null, sonst stuende auf einer Karte eine negative Zahl
        // eingeloester Praemien.
        CustomerCard cc = CustomerCard.create(null, null);
        cc.zaehleBelohnung();
        assertThat(cc.getTotalRewards()).isEqualTo(1);
        cc.nimmBelohnungZurueck();
        assertThat(cc.getTotalRewards()).isZero();
        cc.nimmBelohnungZurueck();
        assertThat(cc.getTotalRewards()).isZero();
    }

    @Test
    void resetLoeschtAuchPunkte() {
        // Der Reset-Knopf im Scanner setzt die Karte komplett zurueck. Bei
        // einer Punktekarte muss das die Punkte mitnehmen, sonst bleibt ein
        // Guthaben auf einer angeblich frischen Karte stehen.
        CustomerCard cc = CustomerCard.create(null, null);
        cc.addPoints(25_000);
        cc.resetAll();
        assertThat(cc.getPointsX100()).isZero();
    }
}
