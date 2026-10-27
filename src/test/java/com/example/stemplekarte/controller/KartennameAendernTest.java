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
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Der Kartenname laesst sich nachtraeglich aendern (PUT /cards/{id}/info).
 *
 * Vorher kannte die Anfrage nur Belohnung und Stempelzahl, der Name war nach
 * dem Anlegen fest. Festgenagelt: Name wird gekuerzt gespeichert, leer oder
 * zu lang wird abgelehnt (und nichts gespeichert), und die Antwort enthaelt
 * den Namen - daran erkennt das Frontend, dass das Backend das Feld kennt.
 */
class KartennameAendernTest {

    private final CardService cardService = mock(CardService.class);
    private final GoogleWalletService google = mock(GoogleWalletService.class);
    private final StampDesignController controller = new StampDesignController(
            mock(ShopRepository.class), cardService, mock(CloudinaryService.class), google);

    private Authentication auth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new JwtAuthFilter.ShopPrincipal(mock(Shop.class)));
        return auth;
    }

    private Card karte(String name) {
        Card card = mock(Card.class);
        when(card.getName()).thenReturn(name);
        when(card.getRewardText()).thenReturn("Gratis Kaffee");
        when(card.getRewardThreshold()).thenReturn(10);
        when(cardService.getByIdAndShop(anyString(), any())).thenReturn(card);
        return card;
    }

    @Test
    void nameWirdGekuerztGespeichertUndAntwortetMitName() {
        Card card = karte("Neuer Name");

        Map<String, Object> antwort = controller.updateCardInfo("CARD-1",
                new StampDesignController.CardInfoRequest("  Neuer Name  ", null, null), auth());

        verify(card).setName("Neuer Name");
        verify(cardService).save(card);
        verify(google).refreshClassForCard(card);
        assertThat(antwort).containsEntry("name", "Neuer Name");
    }

    @Test
    void leererNameWirdAbgelehntUndNichtGespeichert() {
        Card card = karte("Alt");

        assertThatThrownBy(() -> controller.updateCardInfo("CARD-1",
                new StampDesignController.CardInfoRequest("   ", null, null), auth()))
                .isInstanceOf(ResponseStatusException.class);

        verify(card, never()).setName(anyString());
        verify(cardService, never()).save(any());
    }

    @Test
    void zuLangerNameWirdAbgelehnt() {
        Card card = karte("Alt");

        assertThatThrownBy(() -> controller.updateCardInfo("CARD-1",
                new StampDesignController.CardInfoRequest("x".repeat(19), null, null), auth()))
                .isInstanceOf(ResponseStatusException.class);

        verify(card, never()).setName(anyString());
    }

    @Test
    void ohneNameBleibtDerNameUnangetastet() {
        Card card = karte("Alt");

        controller.updateCardInfo("CARD-1",
                new StampDesignController.CardInfoRequest(null, "Neue Belohnung", null), auth());

        verify(card, never()).setName(anyString());
        verify(card).setRewardText("Neue Belohnung");
    }
}
