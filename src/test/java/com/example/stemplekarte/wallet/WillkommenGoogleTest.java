package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

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
}
