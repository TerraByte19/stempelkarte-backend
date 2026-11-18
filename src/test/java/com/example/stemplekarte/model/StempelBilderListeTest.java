package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Mehrere eigene Stempel-Bilder an einer Karte.
 *
 * Wichtigster Punkt: bestehende Karten haben keine Liste, nur das eine Bild
 * von frueher. Fuer sie darf sich nichts aendern. Und das alte Einzelfeld
 * muss immer zum ersten Listeneintrag passen, weil aeltere Stellen (und ein
 * aelteres Panel) nur dieses Feld kennen.
 */
class StempelBilderListeTest {

    private Card karte() {
        return Card.create(mock(Shop.class), "Karte", "Beschreibung", 10, "Gratis");
    }

    @Test
    void neueKarteHatKeineBilder() {
        assertThat(karte().getStampIconListe()).isEmpty();
    }

    @Test
    void alteKarteMitEinemBildLiefertGenauDieses() {
        Card card = karte();
        card.setStampIconUrl("https://bild/eins.png");

        assertThat(card.getStampIconListe()).containsExactly("https://bild/eins.png");
    }

    @Test
    void listeBleibtInReihenfolgeUndErstesStehtImEinzelfeld() {
        Card card = karte();
        card.setStampIconListe(List.of("https://bild/erdbeere.png", "https://bild/keks.png"));

        assertThat(card.getStampIconListe())
                .containsExactly("https://bild/erdbeere.png", "https://bild/keks.png");
        assertThat(card.getStampIconUrl()).isEqualTo("https://bild/erdbeere.png");
    }

    @Test
    void leereListeEntferntAlleBilder() {
        Card card = karte();
        card.setStampIconListe(List.of("https://bild/a.png", "https://bild/b.png"));
        card.setStampIconListe(List.of());

        assertThat(card.getStampIconListe()).isEmpty();
        assertThat(card.getStampIconUrl()).isNull();
    }

    @Test
    void altesEinzelfeldErsetztDasErsteBildDerListe() {
        Card card = karte();
        card.setStampIconListe(List.of("https://bild/a.png", "https://bild/b.png"));
        card.setStampIconUrl("https://bild/neu.png");

        assertThat(card.getStampIconListe())
                .containsExactly("https://bild/neu.png", "https://bild/b.png");
    }

    @Test
    void originalFaelltWegWennDasErsteBildWechselt() {
        Card card = karte();
        card.setStampIconListe(List.of("https://bild/a.png", "https://bild/b.png"));
        card.setStampIconOriginalUrl("https://bild/a-original.png");

        // Nur hinten etwas geaendert: Original passt weiter.
        card.setStampIconListe(List.of("https://bild/a.png"));
        assertThat(card.getStampIconOriginalUrl()).isEqualTo("https://bild/a-original.png");

        card.setStampIconListe(List.of("https://bild/b.png"));
        assertThat(card.getStampIconOriginalUrl()).isNull();
    }

    @Test
    void mehrAlsDasMaximumWirdAbgelehntUndNichtsGeaendert() {
        Card card = karte();
        card.setStampIconListe(List.of("https://bild/a.png"));

        List<String> zuViele = new ArrayList<>();
        for (int i = 0; i <= Card.MAX_STEMPEL_BILDER; i++) zuViele.add("https://bild/" + i + ".png");

        assertThatThrownBy(() -> card.setStampIconListe(zuViele))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(card.getStampIconListe()).containsExactly("https://bild/a.png");
    }
}
