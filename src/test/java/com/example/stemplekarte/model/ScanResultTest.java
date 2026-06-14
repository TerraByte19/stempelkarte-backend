package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests für die ScanResult-Records. Stellt sicher, dass die drei Varianten
 * (Stamped/Redeemed/Full) das gemeinsame Interface korrekt umsetzen und den
 * rewardsEarnedThisScan-Wert tragen — der entscheidet, ob die "Karte voll"-
 * Benachrichtigung ausgelöst wird.
 */
class ScanResultTest {

    @Test
    void stamped_traegtFelderKorrekt() {
        ScanResult result = new ScanResult.Stamped(null, "Stempel hinzugefuegt (3/10)", 0);
        assertThat(result.message()).isEqualTo("Stempel hinzugefuegt (3/10)");
        assertThat(result.rewardsEarnedThisScan()).isEqualTo(0);
    }

    @Test
    void full_signalisiertBelohnung() {
        ScanResult result = new ScanResult.Full(null, "Karte voll! Gratis Kaffee", 1);
        // Bei voller Karte ist rewardsEarnedThisScan > 0 → löst Benachrichtigung aus
        assertThat(result.rewardsEarnedThisScan()).isEqualTo(1);
        assertThat(result.rewardsEarnedThisScan()).isGreaterThan(0);
    }

    @Test
    void redeemed_kannBelohnungAusUeberzugTragen() {
        // Überzieh-Fall (8+4): finales Ergebnis kann Redeemed/Stamped sein,
        // trägt aber trotzdem die im Scan verdiente Belohnung.
        ScanResult result = new ScanResult.Redeemed(null, "Gratis Kaffee eingeloest!", 1);
        assertThat(result.rewardsEarnedThisScan()).isEqualTo(1);
    }

    @Test
    void alleVarianten_implementierenInterface() {
        ScanResult stamped = new ScanResult.Stamped(null, "a", 0);
        ScanResult redeemed = new ScanResult.Redeemed(null, "b", 0);
        ScanResult full = new ScanResult.Full(null, "c", 1);

        // Alle drei sind ScanResult und liefern message() + rewardsEarnedThisScan()
        assertThat(stamped).isInstanceOf(ScanResult.class);
        assertThat(redeemed).isInstanceOf(ScanResult.class);
        assertThat(full).isInstanceOf(ScanResult.class);
        assertThat(stamped.message()).isEqualTo("a");
        assertThat(redeemed.message()).isEqualTo("b");
        assertThat(full.message()).isEqualTo("c");
    }
}