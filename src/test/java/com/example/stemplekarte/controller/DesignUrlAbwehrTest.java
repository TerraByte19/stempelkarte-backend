package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.wallet.CloudinaryService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Beweist, dass die Pruefung im Controller wirklich haengt.
 *
 * BildUrlTest prueft die Regel, dieser Test den Weg dorthin: ohne den
 * Aufruf in updateCardDesign nuetzt die beste Regel nichts - genau so ist
 * die Luecke entstanden, als das Streifenbild als frei setzbares Feld
 * dazukam.
 */
class DesignUrlAbwehrTest {

    private final CardService cardService = mock(CardService.class);
    private final StampDesignController controller = new StampDesignController(
            mock(ShopRepository.class), cardService,
            mock(CloudinaryService.class), mock(GoogleWalletService.class));

    private Authentication auth() {
        Shop shop = mock(Shop.class);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new JwtAuthFilter.ShopPrincipal(shop));
        return auth;
    }

    private StampDesignController.DesignRequest mitStreifenbild(String url) {
        return new StampDesignController.DesignRequest(
                "foto", null, null, null, null, null, null, null, url);
    }

    @Test
    void interneAdresseWirdAbgelehntUndNichtGespeichert() {
        Card card = mock(Card.class);
        when(cardService.getByIdAndShop(anyString(), any())).thenReturn(card);

        assertThatThrownBy(() ->
                controller.updateCardDesign("CARD-1", mitStreifenbild("http://169.254.169.254/latest/meta-data/"), auth()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stripImageUrl");

        verify(card, never()).setStripImageUrl(anyString());
        verify(cardService, never()).save(any());
    }

    @Test
    void leeresFeldEntferntDasBildWeiterhin() {
        Card card = mock(Card.class);
        when(cardService.getByIdAndShop(anyString(), any())).thenReturn(card);

        controller.updateCardDesign("CARD-1", mitStreifenbild(""), auth());

        verify(card).setStripImageUrl(null);
        assertThat(true).isTrue();
    }
}
