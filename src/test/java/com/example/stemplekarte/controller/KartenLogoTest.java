package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Seit dem Karten-Design liegen Logo und Farbe an der Karte. Die Kundenseiten
 * lasen noch den Laden - bei Laeden, die ihr Logo nur im Karten-Design
 * hochgeladen haben (Kaffeberg, 10/2026), fehlte es auf /karte-neu/.
 */
class KartenLogoTest {

    private Card karte(String kartenLogo, String ladenLogo) {
        Shop shop = mock(Shop.class);
        when(shop.getLogoUrl()).thenReturn(ladenLogo);
        when(shop.getColorBackground()).thenReturn("#111111");
        Card card = mock(Card.class);
        when(card.getShop()).thenReturn(shop);
        when(card.getLogoUrl()).thenReturn(kartenLogo);
        return card;
    }

    @Test
    void kartenLogoGewinnt() {
        assertThat(LandingController.logoVon(karte("https://k/logo.png", "https://l/logo.png")))
                .isEqualTo("https://k/logo.png");
    }

    @Test
    void ohneKartenLogoKommtDasLadenLogo() {
        assertThat(LandingController.logoVon(karte(null, "https://l/logo.png"))).isEqualTo("https://l/logo.png");
        assertThat(LandingController.logoVon(karte("  ", "https://l/logo.png"))).isEqualTo("https://l/logo.png");
    }

    @Test
    void ohneJedesLogoLeer() {
        assertThat(LandingController.logoVon(karte(null, null))).isEmpty();
    }

    @Test
    void kartenFarbeGewinnt() {
        Card card = karte(null, null);
        when(card.getColorBackground()).thenReturn("#C0FFEE");
        assertThat(LandingController.farbeVon(card)).isEqualTo("#C0FFEE");
    }
}
