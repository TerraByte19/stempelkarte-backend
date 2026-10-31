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
 * Kartenname nachtraeglich aendern ueber PUT /cards/{id}/info.
 *
 * Vorher nahm der Endpunkt nur Belohnung und Stempelzahl an - ein Laden,
 * der sich beim Anlegen vertippt hatte, musste die Karte loeschen und neu
 * anlegen, und alle Kunden verloren ihre Stempel. Der Name ist bei Google
 * Wallet der grosse Titel der Karte (programName), deshalb muss nach dem
 * Speichern die Class neu geschrieben werden, sonst sehen Android-Kunden
 * weiter den alten Namen.
 *
 * Ein leerer Name aendert nichts: ein aelteres Panel schickt das Feld gar
 * nicht, und eine Karte ohne Titel waere in Google Wallet kaputt.
 */
class KarteUmbenennenTest {

    private final CardService cardService = mock(CardService.class);
    private final GoogleWalletService google = mock(GoogleWalletService.class);
    private final StampDesignController controller = new StampDesignController(
            mock(ShopRepository.class), cardService,
            mock(CloudinaryService.class), google);

    private final Card card = Card.create(mock(Shop.class), "Kafee-Karte", "Kaffee",
            10, "1 Kaffee gratis");

    private Authentication auth() {
        when(cardService.getByIdAndShop(anyString(), any())).thenReturn(card);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new JwtAuthFilter.ShopPrincipal(mock(Shop.class)));
        return auth;
    }

    @Test
    void neuerNameWirdGespeichertUndBeiGoogleNeuGeschrieben() {
        Map<String, Object> antwort = controller.updateCardInfo("CARD-1",
                new StampDesignController.CardInfoRequest("  Kaffee-Karte ", null, null), auth());

        assertThat(card.getName()).isEqualTo("Kaffee-Karte");
        assertThat(antwort).containsEntry("name", "Kaffee-Karte");
        verify(cardService).save(card);
        verify(google).refreshClassForCard(card);
    }

    @Test
    void zuLangerNameWirdAbgelehntUndNichtGespeichert() {
        Authentication auth = auth();
        String neunzehn = "x".repeat(StampDesignController.MAX_NAME + 1);

        assertThatThrownBy(() -> controller.updateCardInfo("CARD-1",
                new StampDesignController.CardInfoRequest(neunzehn, null, null), auth))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(card.getName()).isEqualTo("Kafee-Karte");
        verify(cardService, never()).save(any());
    }

    @Test
    void ohneNamenBleibtDerAlteStehen() {
        controller.updateCardInfo("CARD-1",
                new StampDesignController.CardInfoRequest(null, "2 Kaffee gratis", null), auth());
        controller.updateCardInfo("CARD-1",
                new StampDesignController.CardInfoRequest("   ", null, null), auth());

        assertThat(card.getName()).isEqualTo("Kafee-Karte");
        assertThat(card.getRewardText()).isEqualTo("2 Kaffee gratis");
    }
}
