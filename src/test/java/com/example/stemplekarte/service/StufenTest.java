package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Stufen: Grenzen, Texte, Einstellung, nur Stempelkarten. */
class StufenTest {

    private final Shop shop = mock(Shop.class);
    private final Card card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");

    private CustomerCard karte(int besuche) {
        CustomerCard cc = CustomerCard.create(mock(Customer.class), card);
        for (int i = 0; i < besuche; i++) {
            if (cc.getStamps() >= 10) cc.redeemReward(10);
            cc.addStamp();
        }
        return cc;
    }

    @Test
    void aus_oderPunktekarte_keineStufe() {
        assertThat(Stufen.von(card, karte(30))).isNull();
        Card punkte = Card.createPoints(shop, "Punkte", "x", 100, PointsRounding.GENAU);
        punkte.updateStufen(true, null, null, 10, null, 25);
        assertThat(punkte.isStufenAktiv()).isFalse();
        assertThat(Stufen.von(punkte, CustomerCard.create(mock(Customer.class), punkte))).isNull();
    }

    @Test
    void standard_bronzeSilberGold_anDenGrenzen() {
        card.updateStufen(true, null, null, 10, null, 25);
        assertThat(Stufen.von(card, karte(0)).name()).isEqualTo("Bronze");
        assertThat(Stufen.von(card, karte(9)).index()).isEqualTo(1);
        assertThat(Stufen.von(card, karte(10)).name()).isEqualTo("Silber");
        assertThat(Stufen.von(card, karte(24)).index()).isEqualTo(2);
        Stufen.Stufe gold = Stufen.von(card, karte(25));
        assertThat(gold.name()).isEqualTo("Gold");
        assertThat(gold.hoechste()).isTrue();
        // Praemien zaehlen als Besuche: 25 Besuche = 2 Praemien + 5 Stempel.
        assertThat(karte(25).getTotalRewards()).isEqualTo(2);
    }

    @Test
    void text_naechsteStufe_oderBesuche() {
        card.updateStufen(true, null, null, 10, null, 25);
        assertThat(Stufen.text(Stufen.von(card, karte(2)), 2)).isEqualTo("Bronze · noch 8 Besuche bis Silber");
        assertThat(Stufen.text(Stufen.von(card, karte(24)), 24)).isEqualTo("Silber · noch 1 Besuch bis Gold");
        assertThat(Stufen.text(Stufen.von(card, karte(27)), 27)).isEqualTo("Gold · 27 Besuche");
    }

    @Test
    void eigeneNamen_undSchwellen() {
        card.updateStufen(true, "Fan", "Stammgast", 5, "Legende", 50);
        assertThat(Stufen.von(card, karte(5)).name()).isEqualTo("Stammgast");
        assertThat(Stufen.von(card, karte(49)).naechsteName()).isEqualTo("Legende");
        assertThat(card.getStufenNamen()).containsExactly("Fan", "Stammgast", "Legende");
    }

    @Test
    void stufe3_immerUeberStufe2() {
        card.updateStufen(true, null, null, 30, null, 20);
        assertThat(card.getStufe3Ab()).isEqualTo(31);
    }

    @Test
    void pruefe_bereichReihenfolgeNamen() {
        Stufen.Einstellung e = Stufen.pruefe("  ", " Silber  Plus ", 10, null, 25);
        assertThat(e.name1()).isNull();
        assertThat(e.name2()).isEqualTo("Silber Plus");
        assertThat(e.name3()).isNull();
        assertThatThrownBy(() -> Stufen.pruefe(null, null, 0, null, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Stufen.pruefe(null, null, 10, null, 1001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Stufen.pruefe(null, null, 25, null, 25))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("später");
        assertThatThrownBy(() -> Stufen.pruefe("x".repeat(21), null, 10, null, 25))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20");
    }

    @Test
    void automat_stufe_markierenUndErkennen() {
        card.updateStufen(true, null, null, 10, null, 25);
        CustomerCard cc = karte(10);
        assertThat(AutoNachrichtTyp.STUFE.markiert(cc)).isFalse();
        AutoNachrichtTyp.STUFE.markiere(cc, java.time.Instant.now());
        assertThat(cc.getStufeBenachrichtigt()).isEqualTo(2);
        assertThat(AutoNachrichtTyp.STUFE.markiert(cc)).isTrue();
        // Gold erreicht: wieder offen.
        for (int i = 0; i < 15; i++) { if (cc.getStamps() >= 10) cc.redeemReward(10); cc.addStamp(); }
        assertThat(Stufen.von(card, cc).index()).isEqualTo(3);
        assertThat(AutoNachrichtTyp.STUFE.markiert(cc)).isFalse();
    }
}
