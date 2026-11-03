package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {name}, {offen}, {praemie} je Karte; ohne Wert bleibt der Satz sauber. */
class PlatzhalterTest {

    private final Shop shop = mock(Shop.class);
    private final Card stempel = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
    private final Card punkte = Card.createPoints(shop, "Punkte", "x", 100, PointsRounding.GENAU);

    private CustomerCard karte(Card card, String name, int stempel) {
        Customer c = mock(Customer.class);
        when(c.getName()).thenReturn(name);
        CustomerCard cc = CustomerCard.create(c, card);
        for (int i = 0; i < stempel; i++) cc.addStamp();
        return cc;
    }

    private Reward praemie(String name, long kosten) {
        Reward r = mock(Reward.class);
        when(r.getName()).thenReturn(name);
        when(r.getCostPointsX100()).thenReturn(kosten);
        return r;
    }

    @Test
    void ohnePlatzhalter_unveraendert() {
        String t = "Heute 20% auf Kuchen";
        assertThat(Platzhalter.enthaelt(t)).isFalse();
        assertThat(Platzhalter.fuelle(t, karte(stempel, "Anna Müller", 3), c -> List.of())).isSameAs(t);
    }

    @Test
    void stempelkarte_nameOffenPraemie() {
        CustomerCard cc = karte(stempel, "Anna Müller", 7);
        assertThat(Platzhalter.fuelle("Hallo {name}, noch {offen} Stempel bis: {praemie}!", cc, c -> List.of()))
                .isEqualTo("Hallo Anna, noch 3 Stempel bis: Gratis Kaffee!");
    }

    @Test
    void ohneNamen_keinHaengendesKomma() {
        CustomerCard cc = karte(stempel, null, 1);
        assertThat(Platzhalter.fuelle("Danke für deine Treue, {name}!", cc, c -> List.of()))
                .isEqualTo("Danke für deine Treue!");
        CustomerCard leer = karte(stempel, "  ", 1);
        assertThat(Platzhalter.fuelle("Hallo {name}, schön dich zu sehen.", leer, c -> List.of()))
                .isEqualTo("Hallo, schön dich zu sehen.");
    }

    @Test
    void punktekarte_praemieAusDemKatalog_offenLeer() {
        CustomerCard cc = karte(punkte, "Ben", 0);
        cc.addPoints(500);
        List<Reward> katalog = List.of(praemie("Croissant", 300), praemie("Torte", 800));
        assertThat(Platzhalter.fuelle("Noch {offen} bis: {praemie}.", cc, c -> katalog))
                .isEqualTo("Noch bis: Croissant.");
        // Nichts erreichbar: das naechste Ziel.
        CustomerCard neu = karte(punkte, "Ben", 0);
        assertThat(Platzhalter.fuelle("Bald: {praemie}", neu, c -> katalog)).isEqualTo("Bald: Croissant");
        // Leerer Katalog: Satz endet sauber.
        assertThat(Platzhalter.fuelle("Deine Prämie wartet: {praemie}.", neu, c -> List.of()))
                .isEqualTo("Deine Prämie wartet.");
    }

    @Test
    void stufe_nameOderLeer() {
        CustomerCard cc = karte(stempel, "Anna", 3);
        assertThat(Platzhalter.fuelle("Du bist jetzt {stufe}!", cc, c -> List.of())).isEqualTo("Du bist jetzt!");
        stempel.updateStufen(true, null, null, 3, null, 9);
        assertThat(Platzhalter.fuelle("Du bist jetzt {stufe} bei uns.", cc, c -> List.of()))
                .isEqualTo("Du bist jetzt Silber bei uns.");
    }

    @Test
    void standardtexte_passenInDieGrenze() {
        for (AutoNachrichtTyp typ : AutoNachrichtTyp.values()) {
            assertThat(typ.standardText().length()).isLessThanOrEqualTo(com.example.stemplekarte.model.Kampagne.TEXT_MAX);
        }
    }
}
