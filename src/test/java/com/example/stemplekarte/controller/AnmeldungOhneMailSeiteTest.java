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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Anmeldeseite eines Ladens mit "Karte ohne Anmeldung": ein Knopf, kein
 * Formular. Fuer alle anderen Laeden muss die Seite bleiben, wie sie war -
 * sie laeuft in echten Laeden.
 */
class AnmeldungOhneMailSeiteTest {

    private CustomerService customerService;
    private LandingController controller;
    private Shop shop;
    private Card card;

    @BeforeEach
    void aufbau() {
        customerService = mock(CustomerService.class);
        CardService cardService = mock(CardService.class);
        GoogleWalletService google = mock(GoogleWalletService.class);
        when(google.generateSaveUrl(any())).thenReturn("");
        controller = new LandingController(customerService, cardService, google,
                mock(RewardService.class));
        shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Café <Nord>");
        card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        when(cardService.getById("CARD-1")).thenReturn(card);
    }

    @Test
    void ohneSchalter_bleibtDasFormularMitNameUndEmail() {
        String html = controller.newCustomerPage("CARD-1", null).getBody();

        assertThat(html).contains("id=\"name\"").contains("id=\"email\"").contains("getCard()");
        assertThat(html).doesNotContain("register-direkt").doesNotContain("stampit_karte_");
    }

    @Test
    void mitSchalter_nurEinKnopfUndKeinFormular() {
        when(shop.isAnmeldungOhneMail()).thenReturn(true);

        String html = controller.newCustomerPage("CARD-1", null).getBody();

        assertThat(html).contains("karteHolen()").contains("/karte-neu/register-direkt")
                .contains("const CARD_ID = \"CARD-1\"")
                .contains("Ohne Anmeldung, ohne E-Mail");
        assertThat(html).doesNotContain("id=\"name\"").doesNotContain("id=\"email\"")
                .doesNotContain("id=\"consent\"").doesNotContain("gsi/client");
        // Merken und vor dem Weiterleiten nachfragen.
        assertThat(html).contains("stampit_karte_").contains("/karte-neu/vorhanden/");
        // Der Ladenname kommt vom Laden und darf kein HTML einschleusen.
        assertThat(html).contains("Café &lt;Nord&gt;").doesNotContain("<Nord>");
        // Kontrastfarben muessen gesetzt sein, sonst ist die Schrift unsichtbar.
        assertThat(html).contains("--ink:");
    }

    @Test
    void mitSchalter_inAndererSprache() {
        when(shop.isAnmeldungOhneMail()).thenReturn(true);

        String html = controller.newCustomerPage("CARD-1", "en").getBody();

        assertThat(html).contains("No sign-up, no email").contains("const LANG = \"en\"");
    }

    @Test
    void anmeldungLiefertLinkUndKundenId() {
        Customer kunde = Customer.createOhneMail();
        CustomerCard cc = CustomerCard.create(kunde, card);
        when(customerService.registerOhneMail("CARD-1", "tr")).thenReturn(cc);

        Map<String, Object> antwort = controller.registerDirekt(
                new LandingController.DirektRequest("CARD-1", "tr"), null);

        assertThat(antwort).containsEntry("status", "ok")
                .containsEntry("customerId", kunde.getId())
                .containsEntry("url", "/karte/" + kunde.getId() + "/CARD-1");
    }

    @Test
    void ohneKartenNummer_wirdNichtsAngelegt() {
        assertThatThrownBy(() -> controller.registerDirekt(
                new LandingController.DirektRequest(" ", null), null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(customerService, never()).registerOhneMail(any(), any());
    }

    @Test
    void vorhandenFragtNurNach() {
        when(customerService.hatKarte("CUST-1", "CARD-1")).thenReturn(true);

        assertThat(controller.karteVorhanden("CUST-1", "CARD-1")).containsEntry("vorhanden", true);
        assertThat(controller.karteVorhanden("CUST-2", "CARD-1")).containsEntry("vorhanden", false);
    }
}
