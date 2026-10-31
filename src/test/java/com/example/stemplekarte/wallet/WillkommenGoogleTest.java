package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.google.api.client.util.Data;
import com.google.api.services.walletobjects.model.Image;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Google-Felder einer gesperrten Karte: Geschenk statt "0/10". Der eigene
 * Text der Kundenkarte gewinnt, ohne Text der Standard - nie ein leeres Feld,
 * das Google beim Patch ablehnen koennte.
 */
class WillkommenGoogleTest {

    private CustomerCard karte(String text) {
        CustomerCard cc = CustomerCard.create(null,
                Card.create(mock(Shop.class), "Kaffee", "x", 10, "Gratis Kaffee"));
        cc.willkommenVormerken(text);
        return cc;
    }

    @Test
    void gesperrt_zeigtGeschenk() {
        var felder = GoogleWalletService.willkommenFelder(karte("Erster Tee gratis"));
        assertThat(felder[0].getLabel()).isEqualTo("Willkommen");
        assertThat(felder[0].getBalance().getString()).isEqualTo("Erster Tee gratis");
        assertThat(felder[1].getBalance().getString()).isEqualTo("An der Kasse vorzeigen");
    }

    @Test
    void ohneText_standard() {
        var felder = GoogleWalletService.willkommenFelder(karte(null));
        assertThat(felder[0].getBalance().getString()).isEqualTo(Card.WILLKOMMEN_STANDARD);
    }

    @Test
    void eigenerTitelUndHinweis() {
        CustomerCard cc = karte(null);
        cc.getCard().updateWillkommenDesign("Hallo!", "Gilt bis Monatsende", null, null, null, null);
        var felder = GoogleWalletService.willkommenFelder(cc);
        assertThat(felder[0].getLabel()).isEqualTo("Hallo!");
        assertThat(felder[1].getBalance().getString()).isEqualTo("Gilt bis Monatsende");
    }

    @Test
    void hero_normaleKarte_unberuehrt() {
        // Ohne Willkommens-Historie wird das Feld nicht angefasst: die Paesse
        // in echten Laeden bleiben, wie sie sind.
        CustomerCard cc = CustomerCard.create(null,
                Card.create(mock(Shop.class), "Kaffee", "x", 10, "Gratis Kaffee"));
        assertThat(GoogleWalletService.willkommenHero(cc, true)).isNull();
    }

    @Test
    void hero_gesperrtMitBild() {
        CustomerCard cc = karte(null);
        cc.getCard().updateWillkommenDesign(null, null, "https://res.cloudinary.com/x/w.png", null, null, null);
        Image hero = GoogleWalletService.willkommenHero(cc, true);
        assertThat(hero.getSourceUri().getUri()).isEqualTo("https://res.cloudinary.com/x/w.png");
    }

    @Test
    void hero_gesperrtOhneOderKaputtesBild_wirdGeloescht() {
        CustomerCard ohne = karte(null);
        assertThat(Data.isNull(GoogleWalletService.willkommenHero(ohne, false))).isTrue();

        CustomerCard kaputt = karte(null);
        kaputt.getCard().updateWillkommenDesign(null, null, "https://res.cloudinary.com/x/w.png", null, null, null);
        assertThat(Data.isNull(GoogleWalletService.willkommenHero(kaputt, false))).isTrue();
    }

    @Test
    void hero_nachFreischalten_wirdGeloescht() {
        // Explizites null: beim Patch bliebe ein weggelassenes Feld stehen
        // und das Willkommensbild klebte auf der Stempelkarte.
        CustomerCard cc = karte(null);
        cc.getCard().updateWillkommenDesign(null, null, "https://res.cloudinary.com/x/w.png", null, null, null);
        cc.willkommenEinloesen(Instant.now());
        assertThat(Data.isNull(GoogleWalletService.willkommenHero(cc, true))).isTrue();
    }
}
