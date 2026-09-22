package com.example.stemplekarte.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Zahl, die es bisher nicht gab und die dem Laden am meisten sagt: der
 * ausstehende Punktebestand. Das ist die Verpflichtung, die er sich
 * angesammelt hat - was seine Kunden noch einloesen duerfen.
 *
 * Dazu gehoert ein Fehler, der beim Bauen der Punktekarte aufgefallen ist:
 * vorher lief jede Karte durch dieselbe Schleife, und weil eine Punktekarte
 * reward_threshold = 1 traegt, erhoehte dort jede eingeloeste Praemie die
 * "vergebenen Stempel" um eins. Punktekarten laufen jetzt in einen eigenen
 * Zweig; die Stempel-Kennzahlen sehen sie gar nicht mehr.
 */
class PunkteStatistikTest {

    @Test
    void ausstehenderBestandIstDieSummeDerStaende() {
        // 340 + 50 + 0 Punkte auf drei Karten
        assertThat(StatsService.ausstehendePunkte(List.of(34_000L, 5_000L, 0L)))
                .isEqualTo(39_000L);
    }

    @Test
    void ohneKartenIstDerBestandNull() {
        assertThat(StatsService.ausstehendePunkte(List.of())).isZero();
    }

    @Test
    void eineEinzelneKarteZaehltVoll() {
        assertThat(StatsService.ausstehendePunkte(List.of(12_345L))).isEqualTo(12_345L);
    }
}
