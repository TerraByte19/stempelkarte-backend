package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Welche Karte in welche Gruppe faellt. "jetzt" wird in die Zukunft
 * geschoben, um eine Pause zu simulieren (wie im ZielgruppeTest).
 */
class KundengruppeTest {

    private final Shop shop = mock(Shop.class);
    private final Card stempel = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
    private final Instant jetzt = Instant.now();
    private final Kundengruppe.Regeln regeln = Kundengruppe.Regeln.STANDARD;

    private CustomerCard karte(int stempel) {
        CustomerCard cc = CustomerCard.create(mock(Customer.class), this.stempel);
        for (int i = 0; i < stempel; i++) cc.addStamp();
        return cc;
    }

    private Instant in(int tage) { return jetzt.plus(Duration.ofDays(tage)); }

    private Kundengruppe gruppe(CustomerCard cc, Instant wann) {
        return Kundengruppe.von(cc, Kundengruppe.besucheStempelkarte(stempel, cc), regeln, wann);
    }

    @Test
    void von_leerIstNull_unbekanntKnallt() {
        assertThat(Kundengruppe.von((String) null)).isNull();
        assertThat(Kundengruppe.von(" ")).isNull();
        assertThat(Kundengruppe.von("wird_ruhig")).isEqualTo(Kundengruppe.WIRD_RUHIG);
        assertThatThrownBy(() -> Kundengruppe.von("vip")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neu_inDenErsten14Tagen_dannGelegentlich() {
        CustomerCard cc = karte(1);
        assertThat(gruppe(cc, jetzt)).isEqualTo(Kundengruppe.NEU);
        assertThat(gruppe(cc, in(13))).isEqualTo(Kundengruppe.NEU);
        assertThat(gruppe(cc, in(15))).isEqualTo(Kundengruppe.GELEGENTLICH);
    }

    @Test
    void stammgast_abFuenfBesuchen_auchWennNeu() {
        assertThat(gruppe(karte(4), jetzt)).isEqualTo(Kundengruppe.NEU);
        assertThat(gruppe(karte(5), jetzt)).isEqualTo(Kundengruppe.STAMMGAST);
    }

    @Test
    void besuche_zaehlenPraemienMit() {
        CustomerCard cc = karte(10);
        cc.redeemReward(10);
        for (int i = 0; i < 3; i++) cc.addStamp();
        assertThat(Kundengruppe.besucheStempelkarte(stempel, cc)).isEqualTo(13);
        assertThat(gruppe(cc, jetzt)).isEqualTo(Kundengruppe.STAMMGAST);
    }

    @Test
    void ruhigUndVerloren_gehenVor_auchBeimStammgast() {
        CustomerCard cc = karte(7);
        assertThat(gruppe(cc, in(29))).isEqualTo(Kundengruppe.STAMMGAST);
        assertThat(gruppe(cc, in(31))).isEqualTo(Kundengruppe.WIRD_RUHIG);
        assertThat(gruppe(cc, in(89))).isEqualTo(Kundengruppe.WIRD_RUHIG);
        assertThat(gruppe(cc, in(91))).isEqualTo(Kundengruppe.VERLOREN);
    }

    @Test
    void punktekarte_besucheVonAussen() {
        Card punkte = Card.createPoints(shop, "Punkte", "x", 100, com.example.stemplekarte.model.PointsRounding.GENAU);
        CustomerCard cc = CustomerCard.create(mock(Customer.class), punkte);
        assertThat(Kundengruppe.von(cc, 2, regeln, jetzt)).isEqualTo(Kundengruppe.NEU);
        assertThat(Kundengruppe.von(cc, 5, regeln, jetzt)).isEqualTo(Kundengruppe.STAMMGAST);
    }

    @Test
    void regeln_eigeneWerte() {
        Kundengruppe.Regeln eng = new Kundengruppe.Regeln(3, 7, 14);
        CustomerCard cc = karte(3);
        assertThat(Kundengruppe.von(cc, 3, eng, jetzt)).isEqualTo(Kundengruppe.STAMMGAST);
        assertThat(Kundengruppe.von(cc, 3, eng, in(8))).isEqualTo(Kundengruppe.WIRD_RUHIG);
        assertThat(Kundengruppe.von(cc, 3, eng, in(15))).isEqualTo(Kundengruppe.VERLOREN);
    }

    @Test
    void regeln_pruefung() {
        new Kundengruppe.Regeln(2, 7, 14).pruefe();
        new Kundengruppe.Regeln(50, 180, 365).pruefe();
        assertThatThrownBy(() -> new Kundengruppe.Regeln(1, 30, 90).pruefe())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Stammgast");
        assertThatThrownBy(() -> new Kundengruppe.Regeln(5, 6, 90).pruefe())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ruhig");
        assertThatThrownBy(() -> new Kundengruppe.Regeln(5, 30, 366).pruefe())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Verloren");
        assertThatThrownBy(() -> new Kundengruppe.Regeln(5, 30, 30).pruefe())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("später");
    }

    @Test
    void regeln_vomLaden_mockOderLeerIstStandard() {
        assertThat(Kundengruppe.Regeln.von(shop)).isEqualTo(Kundengruppe.Regeln.STANDARD);
        when(shop.getGruppeStammAb()).thenReturn(3);
        when(shop.getGruppeRuhigTage()).thenReturn(14);
        when(shop.getGruppeVerlorenTage()).thenReturn(60);
        assertThat(Kundengruppe.Regeln.von(shop)).isEqualTo(new Kundengruppe.Regeln(3, 14, 60));
    }

    @Test
    void regeln_vomEchtenLaden_standardWennLeer() {
        Shop echt = Shop.create("a@b.de", "x", "Laden", 3);
        assertThat(Kundengruppe.Regeln.von(echt)).isEqualTo(Kundengruppe.Regeln.STANDARD);
        echt.updateKundengruppen(4, 21, 60);
        assertThat(Kundengruppe.Regeln.von(echt)).isEqualTo(new Kundengruppe.Regeln(4, 21, 60));
    }
}
