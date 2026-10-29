package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Nagelt den Zustand "Willkommensgeschenk offen" fest.
 *
 * Die wichtigste Zusage dahinter: eine Kundenkarte ist von Haus aus NICHT
 * gesperrt. Die Spalten kommen per ddl-auto in eine Tabelle mit echten
 * Karten aus echten Laeden - ein falscher Default haette jede davon
 * gesperrt und an der Kasse unbenutzbar gemacht.
 *
 * Zweite Zusage: das Geschenk gibt es genau einmal.
 */
class WillkommenKarteTest {

    private final Shop shop = mock(Shop.class);

    private Card stempelkarte() {
        return Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
    }

    @Test
    void neueKarte_hatKeinAngebot() {
        Card card = stempelkarte();
        assertThat(card.isWillkommenAktiv()).isFalse();
        assertThat(card.getWillkommenText()).isEqualTo(Card.WILLKOMMEN_STANDARD);
    }

    @Test
    void angebotAn_mitEigenemText() {
        Card card = stempelkarte();
        card.updateWillkommen(true, "  Erster Tee gratis ");
        assertThat(card.isWillkommenAktiv()).isTrue();
        assertThat(card.getWillkommenText()).isEqualTo("Erster Tee gratis");
    }

    @Test
    void leererText_istStandard() {
        Card card = stempelkarte();
        card.updateWillkommen(true, "   ");
        assertThat(card.getWillkommenText()).isEqualTo(Card.WILLKOMMEN_STANDARD);
    }

    @Test
    void punktekarte_istNieAktiv() {
        Card card = Card.createPoints(shop, "Punkte", "x", 100, PointsRounding.GENAU);
        card.updateWillkommen(true, "Gratis");
        assertThat(card.isWillkommenAktiv()).isFalse();
    }

    @Test
    void neueKundenkarte_istNichtGesperrt() {
        CustomerCard cc = CustomerCard.create(null, stempelkarte());
        assertThat(cc.isWillkommenOffen()).isFalse();
        assertThat(cc.getWillkommenEingeloestAt()).isNull();
        assertThat(cc.istFrischFreigeschaltet()).isFalse();
    }

    @Test
    void vormerken_sperrtUndMerktDenText() {
        CustomerCard cc = CustomerCard.create(null, stempelkarte());
        cc.willkommenVormerken("Erster Kaffee geht auf uns");
        assertThat(cc.isWillkommenOffen()).isTrue();
        assertThat(cc.getWillkommenText()).isEqualTo("Erster Kaffee geht auf uns");
    }

    @Test
    void einloesen_schaltetFreiUndZaehltAlsBesuch() {
        CustomerCard cc = CustomerCard.create(null, stempelkarte());
        cc.willkommenVormerken("Gratis");
        Instant jetzt = Instant.parse("2026-10-29T10:00:00Z");

        cc.willkommenEinloesen(jetzt);

        assertThat(cc.isWillkommenOffen()).isFalse();
        assertThat(cc.getWillkommenEingeloestAt()).isEqualTo(jetzt);
        assertThat(cc.getUpdatedAt()).isEqualTo(jetzt);
        assertThat(cc.getLastVisitAt()).isEqualTo(jetzt);
        // Startstand nach dem Geschenk: 0 Stempel, keine Belohnung.
        assertThat(cc.getStamps()).isZero();
        assertThat(cc.getTotalRewards()).isZero();
        assertThat(cc.istFrischFreigeschaltet()).isTrue();
    }

    @Test
    void zweitesEinloesen_wirdAbgelehnt() {
        CustomerCard cc = CustomerCard.create(null, stempelkarte());
        cc.willkommenVormerken("Gratis");
        cc.willkommenEinloesen(Instant.now());

        assertThatThrownBy(() -> cc.willkommenEinloesen(Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schon eingelöst");
    }

    @Test
    void einloesenOhneSperre_wirdAbgelehnt() {
        CustomerCard cc = CustomerCard.create(null, stempelkarte());
        assertThatThrownBy(() -> cc.willkommenEinloesen(Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nachErstemStempel_nichtMehrFrisch() {
        CustomerCard cc = CustomerCard.create(null, stempelkarte());
        cc.willkommenVormerken("Gratis");
        cc.willkommenEinloesen(Instant.now());
        cc.addStamp();
        assertThat(cc.istFrischFreigeschaltet()).isFalse();
    }

    @Test
    void nachStempelUndReset_nichtWiederFrisch() {
        CustomerCard cc = CustomerCard.create(null, stempelkarte());
        cc.willkommenVormerken("Gratis");
        cc.willkommenEinloesen(Instant.parse("2026-10-29T10:00:00Z"));
        cc.addStamp();
        cc.resetAll();
        assertThat(cc.getStamps()).isZero();
        assertThat(cc.istFrischFreigeschaltet()).isFalse();
    }

    @Test
    void gesperrteKarte_bekommtKeineRueckholNachricht() {
        CustomerCard cc = CustomerCard.create(null, stempelkarte());
        cc.willkommenVormerken("Gratis");
        Instant weitWeg = Instant.now().plusSeconds(3600L * 24 * 365);
        assertThat(cc.rueckholFaellig(weitWeg)).isFalse();

        // Nach dem Einloesen zaehlt die Pause ab dem Gratis-Besuch ganz normal.
        cc.willkommenEinloesen(Instant.now());
        assertThat(cc.rueckholFaellig(weitWeg)).isTrue();
    }
}
