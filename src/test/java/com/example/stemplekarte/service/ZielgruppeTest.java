package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wer in welche Zielgruppe faellt. "jetzt" wird in die Zukunft geschoben,
 * um eine Pause zu simulieren - die Karte selbst laesst lastVisitAt nicht
 * von aussen setzen, und das soll so bleiben.
 */
class ZielgruppeTest {

    private final Shop shop = mock(Shop.class);
    private final Card stempel = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
    private final Card punkte = Card.createPoints(shop, "Punkte", "x", 100, PointsRounding.GENAU);
    private final Instant jetzt = Instant.now();

    private CustomerCard karte(int stempel) {
        CustomerCard cc = CustomerCard.create(mock(Customer.class), this.stempel);
        for (int i = 0; i < stempel; i++) cc.addStamp();
        return cc;
    }

    private Instant in(int tage) { return jetzt.plus(Duration.ofDays(tage)); }

    private boolean passt(Zielgruppe g, CustomerCard cc, Instant wann) {
        return g.passt(cc, wann, card -> List.of());
    }

    @Test
    void von_leerIstAlle_unbekanntKnallt() {
        assertThat(Zielgruppe.von(null)).isEqualTo(Zielgruppe.ALLE);
        assertThat(Zielgruppe.von(" ")).isEqualTo(Zielgruppe.ALLE);
        assertThat(Zielgruppe.von("inaktiv_30")).isEqualTo(Zielgruppe.INAKTIV_30);
        assertThat(Zielgruppe.von("geburtstag")).isEqualTo(Zielgruppe.GEBURTSTAG);
        assertThatThrownBy(() -> Zielgruppe.von("sternzeichen"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alle_trifftJeden() {
        assertThat(passt(Zielgruppe.ALLE, karte(0), jetzt)).isTrue();
    }

    @Test
    void neu_nurInDenErsten14Tagen() {
        CustomerCard cc = karte(1);
        assertThat(passt(Zielgruppe.NEU, cc, in(1))).isTrue();
        assertThat(passt(Zielgruppe.NEU, cc, in(20))).isFalse();
    }

    @Test
    void geburtstag_imLaufendenMonat() {
        java.time.ZoneId zone = java.time.ZoneId.of("Europe/Berlin");
        java.time.LocalDate heute = jetzt.atZone(zone).toLocalDate();
        CustomerCard mit = karte(1);
        mit.anmeldedatenErgaenzen(heute.minusYears(30).withDayOfMonth(1), null);
        CustomerCard anderer = karte(1);
        anderer.anmeldedatenErgaenzen(heute.minusYears(30).plusMonths(1), null);
        assertThat(passt(Zielgruppe.GEBURTSTAG, mit, jetzt)).isTrue();
        assertThat(passt(Zielgruppe.GEBURTSTAG, anderer, jetzt)).isFalse();
        // Ohne Angabe nie - der Laden fragt den Geburtstag vielleicht gar nicht ab.
        assertThat(passt(Zielgruppe.GEBURTSTAG, karte(1), jetzt)).isFalse();
    }

    @Test
    void inaktiv_nachPause() {
        CustomerCard cc = karte(3);
        assertThat(passt(Zielgruppe.INAKTIV_30, cc, in(10))).isFalse();
        assertThat(passt(Zielgruppe.INAKTIV_30, cc, in(31))).isTrue();
        assertThat(passt(Zielgruppe.INAKTIV_60, cc, in(31))).isFalse();
        assertThat(passt(Zielgruppe.INAKTIV_60, cc, in(61))).isTrue();
        assertThat(passt(Zielgruppe.INAKTIV_90, cc, in(61))).isFalse();
        assertThat(passt(Zielgruppe.INAKTIV_90, cc, in(91))).isTrue();
    }

    @Test
    void einmal_genauEinStempelUndWeg() {
        assertThat(passt(Zielgruppe.EINMAL, karte(1), in(15))).isTrue();
        assertThat(passt(Zielgruppe.EINMAL, karte(1), in(3))).isFalse();
        assertThat(passt(Zielgruppe.EINMAL, karte(2), in(15))).isFalse();
        CustomerCard p = CustomerCard.create(mock(Customer.class), punkte);
        assertThat(passt(Zielgruppe.EINMAL, p, in(15))).isFalse();
    }

    @Test
    void stammRuhig_praemieGeholtUndWeg() {
        CustomerCard cc = karte(10);
        cc.redeemReward(10);
        assertThat(passt(Zielgruppe.STAMM_RUHIG, cc, in(22))).isTrue();
        assertThat(passt(Zielgruppe.STAMM_RUHIG, cc, in(5))).isFalse();
        assertThat(passt(Zielgruppe.STAMM_RUHIG, karte(3), in(22))).isFalse();
    }

    @Test
    void praemieWartet_stempelkarte() {
        assertThat(passt(Zielgruppe.PRAEMIE_WARTET, karte(10), jetzt)).isTrue();
        assertThat(passt(Zielgruppe.PRAEMIE_WARTET, karte(9), jetzt)).isFalse();
    }

    @Test
    void praemieWartet_punktekarteMitKatalog() {
        CustomerCard cc = CustomerCard.create(mock(Customer.class), punkte);
        cc.addPoints(30000);
        List<Reward> katalog = List.of(Reward.create(punkte, "Kaffee", 25000, 0));
        assertThat(Zielgruppe.PRAEMIE_WARTET.passt(cc, jetzt, card -> katalog)).isTrue();
        cc.addPoints(-10000);
        assertThat(Zielgruppe.PRAEMIE_WARTET.passt(cc, jetzt, card -> katalog)).isFalse();
        // Ohne Katalog gibt es nichts zu holen.
        assertThat(Zielgruppe.PRAEMIE_WARTET.passt(cc, jetzt, card -> List.of())).isFalse();
    }

    @Test
    void fastVoll_nochEinOderZwei() {
        assertThat(passt(Zielgruppe.FAST_VOLL, karte(8), jetzt)).isTrue();
        assertThat(passt(Zielgruppe.FAST_VOLL, karte(9), jetzt)).isTrue();
        assertThat(passt(Zielgruppe.FAST_VOLL, karte(7), jetzt)).isFalse();
        assertThat(passt(Zielgruppe.FAST_VOLL, karte(10), jetzt)).isFalse();
    }

    @Test
    void bewertung_nichtWaehlbar_zweiStundenBisDreiTage_genugBesuche_einmal() {
        assertThat(Zielgruppe.BEWERTUNG.waehlbar()).isFalse();
        assertThat(Zielgruppe.waehlbare()).doesNotContain(Zielgruppe.BEWERTUNG);
        Instant spaeter = jetzt.plus(Duration.ofHours(3));
        // Laden hat nichts eingestellt: nie.
        assertThat(passt(Zielgruppe.BEWERTUNG, karte(3), spaeter)).isFalse();
        when(shop.getBewertungErinnerungAb()).thenReturn(3);
        assertThat(passt(Zielgruppe.BEWERTUNG, karte(3), spaeter)).isTrue();
        assertThat(passt(Zielgruppe.BEWERTUNG, karte(2), spaeter)).isFalse();
        // Gerade erst gestempelt: der Stempel-Push ist noch frisch.
        assertThat(passt(Zielgruppe.BEWERTUNG, karte(3), jetzt.plus(Duration.ofMinutes(30)))).isFalse();
        // Zu lange her: erst nach dem naechsten Besuch.
        assertThat(passt(Zielgruppe.BEWERTUNG, karte(3), in(4))).isFalse();
        // Praemie geholt zaehlt als 10 Besuche mehr.
        CustomerCard stamm = karte(10);
        stamm.redeemReward(10);
        assertThat(passt(Zielgruppe.BEWERTUNG, stamm, spaeter)).isTrue();
        // Schon erinnert: nie wieder.
        CustomerCard erinnert = karte(5);
        erinnert.bewertungErinnert(jetzt);
        assertThat(passt(Zielgruppe.BEWERTUNG, erinnert, spaeter)).isFalse();
        // Punktekarte: nur, wer schon eine Praemie geholt hat.
        CustomerCard p = CustomerCard.create(mock(Customer.class), punkte);
        p.addPoints(500);
        assertThat(passt(Zielgruppe.BEWERTUNG, p, spaeter)).isFalse();
    }
}
