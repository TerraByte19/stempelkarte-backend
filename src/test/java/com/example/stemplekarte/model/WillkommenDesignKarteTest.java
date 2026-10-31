package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Design der gesperrten Karte: ohne Angaben gelten die Standards, null im
 * Request laesst stehen, leer setzt zurueck. Genau diese drei Regeln
 * braucht das Panel, sonst loescht ein aelterer Client beim Speichern alles.
 */
class WillkommenDesignKarteTest {

    private Card karte() {
        return Card.create(mock(Shop.class), "Kaffee", "x", 10, "Gratis Kaffee");
    }

    @Test
    void ohneAngaben_standards() {
        Card card = karte();
        assertThat(card.getWillkommenTitel()).isEqualTo("Willkommen");
        assertThat(card.getWillkommenHinweis()).isEqualTo("An der Kasse vorzeigen");
        assertThat(card.getWelcomeTitle()).isNull();
        assertThat(card.getWelcomeImageUrl()).isNull();
        assertThat(card.getWelcomeColorBackground()).isNull();
    }

    @Test
    void setzen_trimmt() {
        Card card = karte();
        card.updateWillkommenDesign(" Hallo! ", " Gilt bis Monatsende ",
                "https://res.cloudinary.com/x/welcome.png", "#112233", "#FFFFFF", "#AABBCC");
        assertThat(card.getWillkommenTitel()).isEqualTo("Hallo!");
        assertThat(card.getWillkommenHinweis()).isEqualTo("Gilt bis Monatsende");
        assertThat(card.getWelcomeImageUrl()).isEqualTo("https://res.cloudinary.com/x/welcome.png");
        assertThat(card.getWelcomeColorBackground()).isEqualTo("#112233");
        assertThat(card.getWelcomeColorForeground()).isEqualTo("#FFFFFF");
        assertThat(card.getWelcomeColorLabel()).isEqualTo("#AABBCC");
    }

    @Test
    void null_laesstStehen_leer_setztZurueck() {
        Card card = karte();
        card.updateWillkommenDesign("Hallo", "Hinweis", "https://a/b.png", "#112233", "#FFFFFF", "#AABBCC");

        card.updateWillkommenDesign(null, null, null, null, null, null);
        assertThat(card.getWelcomeTitle()).isEqualTo("Hallo");
        assertThat(card.getWelcomeImageUrl()).isEqualTo("https://a/b.png");

        card.updateWillkommenDesign("", " ", "", "", "", "");
        assertThat(card.getWillkommenTitel()).isEqualTo("Willkommen");
        assertThat(card.getWillkommenHinweis()).isEqualTo("An der Kasse vorzeigen");
        assertThat(card.getWelcomeImageUrl()).isNull();
        assertThat(card.getWelcomeColorBackground()).isNull();
        assertThat(card.getWelcomeColorForeground()).isNull();
        assertThat(card.getWelcomeColorLabel()).isNull();
    }
}
