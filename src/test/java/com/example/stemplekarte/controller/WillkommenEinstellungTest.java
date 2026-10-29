package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.SentNewsletterRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.EmailService;
import com.example.stemplekarte.service.NewsletterService;
import com.example.stemplekarte.service.RewardService;
import com.example.stemplekarte.service.ShopService;
import com.example.stemplekarte.service.StatsService;
import com.example.stemplekarte.wallet.CloudinaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PUT/GET /api/shop/cards/{id}/willkommen.
 *
 * Der Text landet ungefiltert in Wallet-Pass, Mail und zwei Webseiten.
 * Laenge und Steuerzeichen werden deshalb hier an der Tuer geprueft, nicht
 * an jeder Ausgabestelle einzeln. Punktekarten lehnen ab - fuer sie gibt es
 * keinen gesperrten Pass.
 */
class WillkommenEinstellungTest {

    private CardService cardService;
    private CustomerCardRepository ccRepo;
    private ShopController controller;
    private Shop shop;
    private Authentication auth;

    @BeforeEach
    void aufbau() {
        cardService = mock(CardService.class);
        ccRepo = mock(CustomerCardRepository.class);
        controller = new ShopController(mock(ShopService.class), cardService, ccRepo,
                mock(CloudinaryService.class), mock(EmailService.class),
                mock(SentNewsletterRepository.class), mock(StatsService.class),
                mock(NewsletterService.class), mock(RewardService.class));
        shop = mock(Shop.class);
        auth = new UsernamePasswordAuthenticationToken(
                new JwtAuthFilter.ShopPrincipal(shop), null, List.of());
    }

    private Card stempelkarte() {
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        when(cardService.getByIdAndShop(card.getId(), shop)).thenReturn(card);
        return card;
    }

    @Test
    void einschalten_mitText_undZaehlern() {
        Card card = stempelkarte();
        when(ccRepo.countByCardAndWelcomePendingTrue(card)).thenReturn(3L);
        when(ccRepo.countByCardAndWelcomeRedeemedAtIsNotNull(card)).thenReturn(7L);

        var antwort = controller.updateWillkommen(card.getId(),
                new ShopController.WillkommenRequest(true, " Erster\nTee gratis "), auth);

        assertThat(antwort.enabled()).isTrue();
        assertThat(antwort.text()).isEqualTo("Erster Tee gratis");
        assertThat(antwort.offen()).isEqualTo(3);
        assertThat(antwort.eingeloest()).isEqualTo(7);
        verify(cardService).save(card);
    }

    @Test
    void leererText_standard() {
        Card card = stempelkarte();
        var antwort = controller.updateWillkommen(card.getId(),
                new ShopController.WillkommenRequest(true, ""), auth);
        assertThat(antwort.text()).isEqualTo(Card.WILLKOMMEN_STANDARD);
    }

    @Test
    void zuLang_abgelehnt() {
        Card card = stempelkarte();
        assertThatThrownBy(() -> controller.updateWillkommen(card.getId(),
                new ShopController.WillkommenRequest(true, "x".repeat(61)), auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("60");
        verify(cardService, never()).save(any());
    }

    @Test
    void punktekarte_abgelehnt() {
        Card card = Card.createPoints(shop, "Punkte", "x", 100, PointsRounding.GENAU);
        when(cardService.getByIdAndShop(card.getId(), shop)).thenReturn(card);

        assertThatThrownBy(() -> controller.updateWillkommen(card.getId(),
                new ShopController.WillkommenRequest(true, null), auth))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cardService, never()).save(any());
    }

    @Test
    void ausschalten_laesstZaehlerStehen() {
        Card card = stempelkarte();
        card.updateWillkommen(true, null);
        when(ccRepo.countByCardAndWelcomePendingTrue(card)).thenReturn(2L);

        var antwort = controller.updateWillkommen(card.getId(),
                new ShopController.WillkommenRequest(false, null), auth);

        assertThat(antwort.enabled()).isFalse();
        // Offene Geschenke bleiben offen - das Versprechen gilt.
        assertThat(antwort.offen()).isEqualTo(2);
    }
}
