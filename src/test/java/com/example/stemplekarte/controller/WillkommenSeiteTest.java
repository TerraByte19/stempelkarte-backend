package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.service.RewardService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Baut die Kundenseiten WIRKLICH zusammen, nicht nur die Bausteine.
 *
 * Grund: beide Seiten sind eine einzige formatted()-Vorlage mit Dutzenden
 * Platzhaltern. Ein vergessenes oder vertauschtes Argument wirft erst zur
 * Laufzeit - und der catch darum zeigt dann "Karte nicht gefunden". Fuer
 * JEDEN Kunden, nicht nur fuer gesperrte Karten. Dieser Test sieht das,
 * weil er auf den Inhalt der echten Seite prueft.
 */
class WillkommenSeiteTest {

    private CustomerService customerService;
    private CardService cardService;
    private LandingController controller;
    private Card card;
    private Customer customer;

    @BeforeEach
    void aufbau() {
        customerService = mock(CustomerService.class);
        cardService = mock(CardService.class);
        GoogleWalletService google = mock(GoogleWalletService.class);
        when(google.generateSaveUrl(any())).thenReturn("");
        controller = new LandingController(customerService, cardService, google, mock(RewardService.class));

        Shop shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Café Nord");
        card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        customer = mock(Customer.class);
        when(customer.getName()).thenReturn("Ana");
    }

    private String kundenseite(CustomerCard cc) {
        when(customerService.getOrCreateCustomerCard("CUST-1", "CARD-1")).thenReturn(cc);
        return controller.landingPage("CUST-1", "CARD-1").getBody();
    }

    @Test
    void gesperrt_zeigtGeschenk_versteckteStempel() {
        CustomerCard cc = CustomerCard.create(customer, card);
        cc.willkommenVormerken("Erster <b>Kaffee</b>");

        String html = kundenseite(cc);

        assertThat(html).doesNotContain("Karte nicht gefunden");
        assertThat(html).contains("id=\"welcome-box\"");
        assertThat(html).contains("Erster &lt;b&gt;Kaffee&lt;/b&gt;").doesNotContain("<b>Kaffee</b>");
        assertThat(html).contains("<div id=\"stempel-teil\" class=\"hidden\">");
        assertThat(html).contains("let gesperrt = true;");
        // Raster liegt trotzdem im DOM, damit das Freischalten ohne Reload geht.
        assertThat(html).contains("0 von 10 Stempeln");
    }

    @Test
    void offen_wieBisher() {
        CustomerCard cc = CustomerCard.create(customer, card);
        cc.addStamp();

        String html = kundenseite(cc);

        assertThat(html).doesNotContain("Karte nicht gefunden");
        assertThat(html).doesNotContain("id=\"welcome-box\"");
        assertThat(html).contains("<div id=\"stempel-teil\">");
        assertThat(html).contains("let gesperrt = false;");
        assertThat(html).contains("1 von 10 Stempeln");
        assertThat(html).contains("Noch 9 Stempel bis: Gratis Kaffee");
    }

    @Test
    void anmeldeseite_mitAngebot() {
        card.updateWillkommen(true, "Erster Kaffee geht auf uns");
        when(cardService.getById("CARD-1")).thenReturn(card);

        String html = controller.newCustomerPage("CARD-1").getBody();

        assertThat(html).doesNotContain("Karte nicht gefunden");
        assertThat(html).contains("🎁 Erster Kaffee geht auf uns");
        assertThat(html).contains("Zeig sie an der Kasse");
        assertThat(html).contains("Angebote von Café Nord per E-Mail");
    }

    @Test
    void anmeldeseite_ohneAngebot_wortgleich() {
        when(cardService.getById("CARD-1")).thenReturn(card);

        String html = controller.newCustomerPage("CARD-1").getBody();

        assertThat(html).doesNotContain("Karte nicht gefunden");
        assertThat(html).contains("🎉 Stempelkarte holen!");
        assertThat(html).contains("die Stempelkarte direkt in deine Wallet!");
        assertThat(html).doesNotContain("🎁");
    }

    @Test
    void json_meldetSperre() {
        CustomerCard cc = CustomerCard.create(customer, card);
        cc.willkommenVormerken("x");
        assertThat(CustomerController.CustomerCardResponse.from(cc).willkommenOffen()).isTrue();
        cc.willkommenEinloesen(java.time.Instant.now());
        assertThat(CustomerController.CustomerCardResponse.from(cc).willkommenOffen()).isFalse();
    }
}
