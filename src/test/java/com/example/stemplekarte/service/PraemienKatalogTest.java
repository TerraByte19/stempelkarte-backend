package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Das Ziel auf der Wallet-Karte ist die billigste Praemie, die der Kunde
 * sich noch NICHT leisten kann. Ohne diese Regel stuende dort bei jedem
 * Stand dieselbe Praemie, und der Zugreiz der Stempelkarte ("noch zwei,
 * dann ist der Kuchen drin") ginge verloren.
 *
 * Die Randfaelle sind der eigentliche Grund fuer diesen Test: leerer
 * Katalog und ein Kunde, der sich alles leisten kann.
 */
class PraemienKatalogTest {

    private Card punktekarte() {
        return Card.createPoints(mock(Shop.class), "Bistro", "Punkte",
                100, PointsRounding.GENAU);
    }

    private List<Reward> katalog(Card c) {
        return List.of(
                Reward.create(c, "Kaffee", 10_000, 0),   // 100 Punkte
                Reward.create(c, "Kuchen", 25_000, 1),   // 250 Punkte
                Reward.create(c, "Tasse", 80_000, 2)     // 800 Punkte
        );
    }

    @Test
    void zielIstBilligsteNochNichtBezahlbare() {
        Card c = punktekarte();
        // Stand 340 Punkte: Kaffee (100) ist bezahlt, Kuchen (250) auch,
        // also ist die Tasse das naechste Ziel.
        Reward ziel = RewardService.naechstesZiel(katalog(c), 34_000);
        assertThat(ziel.getName()).isEqualTo("Tasse");
    }

    @Test
    void frischeKarte_zieltAufDieBilligste() {
        Card c = punktekarte();
        Reward ziel = RewardService.naechstesZiel(katalog(c), 0);
        assertThat(ziel.getName()).isEqualTo("Kaffee");
    }

    @Test
    void allesBezahlbar_zieltAufDieTeuerste() {
        Card c = punktekarte();
        Reward ziel = RewardService.naechstesZiel(katalog(c), 99_900);
        assertThat(ziel.getName()).isEqualTo("Tasse");
    }

    @Test
    void leererKatalog_hatKeinZiel() {
        assertThat(RewardService.naechstesZiel(List.of(), 34_000)).isNull();
    }

    @Test
    void genauBezahlbar_zaehltAlsErreicht() {
        Card c = punktekarte();
        // Stand exakt 100 Punkte: Kaffee ist bezahlbar, also zielt die Karte
        // schon auf den Kuchen.
        Reward ziel = RewardService.naechstesZiel(katalog(c), 10_000);
        assertThat(ziel.getName()).isEqualTo("Kuchen");
    }
}
