package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.AppleAnmeldung;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Apple-Knopf nur mit Services-ID; Anmeldung mit Apple fuehrt direkt zur Karte; ein Hinweis fuer beide Knoepfe. */
class AppleAnmeldeSeiteTest {

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
        ReflectionTestUtils.setField(controller, "baseUrl", "https://api.example");
        Shop shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Café Nord");
        card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        when(cardService.getById("CARD-1")).thenReturn(card);
    }

    private void apple(String clientId) {
        ReflectionTestUtils.setField(controller, "appleAnmeldung", new AppleAnmeldung(clientId) {
            @Override
            public Profil pruefe(String idToken, String name) {
                if (!"gutes-token".equals(idToken)) throw new IllegalArgumentException("Apple-Anmeldung fehlgeschlagen");
                return new Profil("mo@privaterelay.appleid.com", name == null || name.isBlank() ? "mo" : name);
            }
        });
    }

    private void google(String clientId) {
        ReflectionTestUtils.setField(controller, "googleAnmeldung", new GoogleAnmeldung(clientId));
    }

    @Test
    void ohneClientId_keinKnopf() {
        String html = controller.newCustomerPage("CARD-1", null, null).getBody();
        // Das CSS fuer #appleid-signin steht immer da, der Knopf selbst nicht.
        assertThat(html).doesNotContain("appleid.auth.js").doesNotContain("id='appleid-signin'").doesNotContain("class='sozial'");
        assertThat(html).contains("placeholder=\"Dein Name\"");

        apple("");
        assertThat(controller.newCustomerPage("CARD-1", null, null).getBody()).doesNotContain("appleid.auth.js");
        assertThatThrownBy(() -> controller.registerMitApple(
                new LandingController.AppleRegisterRequest("gutes-token", "Mo", "CARD-1", false, null, null, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mitClientId_knopfUndDirektZurKarte() {
        apple("de.stampit.web");
        String html = controller.newCustomerPage("CARD-1", null, null).getBody();
        assertThat(html).contains("appleid/1/de_DE/appleid.auth.js")
                .contains("clientId: \"de.stampit.web\"")
                .contains("redirectURI: \"https://api.example/karte-neu/apple\"")
                .contains("id='appleid-signin'").contains("onAppleCredential")
                .contains("class='oder'>oder</div>")
                .doesNotContain("gsi/client")                     // Google aus
                .contains("placeholder=\"Dein Name\"");           // E-Mail-Weg bleibt
        // User 2026-11-06: Knoepfe klein unter "Karte holen", kein Erklaertext, Sprachleiste ganz unten.
        assertThat(html).doesNotContain("Bestätigungs-Mail, die Karte ist sofort da");
        assertThat(html.indexOf("onclick=\"getCard()\"")).isLessThan(html.indexOf("class='sozial'"));
        assertThat(html.indexOf("id=\"success\"")).isLessThan(html.indexOf("class='sprachen'"));
        assertThat(html).contains("data-width='200' data-height='36'");
        assertThat(controller.newCustomerPage("CARD-1", "tr", null).getBody())
                .contains("appleid/1/tr_TR/appleid.auth.js");

        Customer kunde = mock(Customer.class);
        when(kunde.getId()).thenReturn("CUST-9");
        CustomerCard cc = CustomerCard.create(kunde, card);
        when(customerService.registerForCardBestaetigt(eq("Mo Khalil"), eq("mo@privaterelay.appleid.com"), eq("CARD-1"),
                anyBoolean(), isNull(), isNull(), isNull(), eq("ar"))).thenReturn(cc);

        Map<String, Object> out = controller.registerMitApple(
                new LandingController.AppleRegisterRequest("gutes-token", "Mo Khalil", "CARD-1", true, null, null, null, "ar"), null);
        assertThat(out).containsEntry("status", "ok").containsEntry("url", "/karte/CUST-9/CARD-1");

        assertThatThrownBy(() -> controller.registerMitApple(
                new LandingController.AppleRegisterRequest("falsch", null, "CARD-1", true, null, null, null, null), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void beideKnoepfe_einOder() {
        apple("de.stampit.web");
        google("123-abc.apps.googleusercontent.com");
        String html = controller.newCustomerPage("CARD-1", null, null).getBody();
        assertThat(html).contains("gsi/client").contains("appleid.auth.js");
        assertThat(html.split("class='oder'", -1)).hasSize(2);   // genau ein "oder"
        // Google steht vor Apple.
        assertThat(html.indexOf("g_id_signin")).isLessThan(html.indexOf("id='appleid-signin'"));
    }

    @Test
    void rueckkehrSeite_nurFensterSchliessen() {
        String html = controller.appleRueckkehr(null).getBody();
        assertThat(html).contains("Fenster schließen").contains("window.close");
        assertThat(controller.appleRueckkehr("en").getBody()).contains("close this window");
        assertThat(LandingController.appleLocale("ar")).isEqualTo("ar_SA");
        assertThat(LandingController.appleLocale("xx")).isEqualTo("de_DE");
    }
}
