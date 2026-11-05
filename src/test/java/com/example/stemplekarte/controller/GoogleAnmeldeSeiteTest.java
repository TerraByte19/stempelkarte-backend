package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.service.GoogleAnmeldung;
import com.example.stemplekarte.service.RewardService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Google-Knopf nur mit Client-ID; die Anmeldung mit Google fuehrt direkt zur Karte. */
class GoogleAnmeldeSeiteTest {

    private CustomerService customerService;
    private CardService cardService;
    private LandingController controller;
    private Card card;

    @BeforeEach
    void aufbau() {
        customerService = mock(CustomerService.class);
        cardService = mock(CardService.class);
        GoogleWalletService google = mock(GoogleWalletService.class);
        when(google.generateSaveUrl(any())).thenReturn("");
        controller = new LandingController(customerService, cardService, google,
                mock(RewardService.class), mock(com.example.stemplekarte.service.EmpfehlungService.class));
        Shop shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Café Nord");
        card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        when(cardService.getById("CARD-1")).thenReturn(card);
    }

    private void google(String clientId) {
        ReflectionTestUtils.setField(controller, "googleAnmeldung", new GoogleAnmeldung(clientId) {
            @Override
            public Profil pruefe(String idToken) {
                if (!"gutes-token".equals(idToken)) throw new IllegalArgumentException("Google-Anmeldung fehlgeschlagen");
                return new Profil("ayse@example.com", "Ayşe");
            }
        });
    }

    @Test
    void ohneClientId_keinKnopf() {
        String html = controller.newCustomerPage("CARD-1").getBody();
        assertThat(html).doesNotContain("gsi/client").doesNotContain("g_id_signin");
        assertThat(html).contains("placeholder=\"Dein Name\"");

        google("");
        assertThat(controller.newCustomerPage("CARD-1").getBody()).doesNotContain("gsi/client");
        assertThatThrownBy(() -> controller.registerMitGoogle(
                new LandingController.GoogleRegisterRequest("gutes-token", "CARD-1", false, null, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mitClientId_knopfUndDirektZurKarte() {
        google("123-abc.apps.googleusercontent.com");
        String html = controller.newCustomerPage("CARD-1").getBody();
        assertThat(html).contains("gsi/client")
                .contains("data-client_id='123-abc.apps.googleusercontent.com'")
                .contains("g_id_signin").contains("onGoogleCredential")
                .contains("const CARD_ID = \"CARD-1\"")
                .contains("placeholder=\"Dein Name\"");   // E-Mail-Weg bleibt

        Customer kunde = mock(Customer.class);
        when(kunde.getId()).thenReturn("CUST-9");
        CustomerCard cc = CustomerCard.create(kunde, card);
        when(customerService.registerForCardBestaetigt(eq("Ayşe"), eq("ayse@example.com"), eq("CARD-1"),
                anyBoolean(), isNull(), isNull(), isNull())).thenReturn(cc);

        Map<String, Object> out = controller.registerMitGoogle(
                new LandingController.GoogleRegisterRequest("gutes-token", "CARD-1", true, null, null, null), null);
        assertThat(out).containsEntry("status", "ok").containsEntry("url", "/karte/CUST-9/CARD-1");

        assertThatThrownBy(() -> controller.registerMitGoogle(
                new LandingController.GoogleRegisterRequest("falsch", "CARD-1", true, null, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
