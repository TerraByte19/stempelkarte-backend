package com.example.stemplekarte.controller;

import com.example.stemplekarte.i18n.Texts;
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
 * Kundenseiten in der Sprache des Ladens (oder des Kunden). Baut die echten
 * Seiten zusammen: ein vertauschtes formatted()-Argument faellt sonst erst
 * beim Kunden auf.
 */
class KundenSprachenTest {

    private CustomerService customerService;
    private CardService cardService;
    private LandingController controller;
    private Shop shop;
    private Customer customer;

    @BeforeEach
    void aufbau() {
        customerService = mock(CustomerService.class);
        cardService = mock(CardService.class);
        GoogleWalletService google = mock(GoogleWalletService.class);
        when(google.generateSaveUrl(any())).thenReturn("https://pay.google.com/save/x");
        var empfehlung = mock(com.example.stemplekarte.service.EmpfehlungService.class);
        when(empfehlung.link(any())).thenReturn("https://stampit.test/karte-neu/CARD-1?ref=ABC234");
        controller = new LandingController(customerService, cardService, google, mock(RewardService.class), empfehlung);

        shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Kahve Köşesi");
        when(shop.isDemo()).thenReturn(true);
        customer = mock(Customer.class);
        when(customer.getName()).thenReturn("Ayşe");
        when(customer.getLanguage()).thenReturn("tr");
    }

    private String kartenseite(Card card) {
        CustomerCard cc = CustomerCard.create(customer, card);
        cc.addStamp();
        when(customerService.getOrCreateCustomerCard("CUST-1", "CARD-1")).thenReturn(cc);
        String html = controller.landingPage("CUST-1", "CARD-1", null).getBody();
        assertThat(html).doesNotContain("Karte nicht gefunden");
        assertThat(html).contains("class='sprachen'").contains("?lang=ar");
        return html;
    }

    @Test
    void stempelkarte_tuerkischerLaden() {
        String html = kartenseite(Card.create(shop, "Kahve", "x", 10, "Bir kahve bedava"));
        assertThat(html).contains("<html lang=\"tr\" dir=\"ltr\">");
        assertThat(html).contains(Texts.t("tr", "stamp.progress", 1, 10));
        assertThat(html).contains(Texts.t("tr", "card.apple"));
        assertThat(html).doesNotContain("von 10 Stempeln").doesNotContain("Zu Apple Wallet");
        // JS-Vorlagen als JS-Zeichenketten, Platzhalter bleiben fuers Einsetzen
        assertThat(html).contains("const progressTpl = \"").contains("{0}").contains("{1}");
        // Demo-Knopf und Status in Tuerkisch, kein roher Text im Inline-JS
        assertThat(html).contains(Texts.t("tr", "card.demo_next")).doesNotContain("Nächster Schritt");
    }

    @Test
    void punkte_mitglied_paket_tuerkisch() {
        String punkte = kartenseite(Card.createPoints(shop, "Puan", "x", 100, com.example.stemplekarte.model.PointsRounding.values()[0]));
        assertThat(punkte).contains("lang=\"tr\"").contains(Texts.t("tr", "points.balance"))
                .doesNotContain("Punktestand");

        String mitglied = kartenseite(Card.createMember(shop, "Üye", "x"));
        assertThat(mitglied).contains(Texts.t("tr", "member.since")).doesNotContain("Mitglied seit");

        String paket = kartenseite(Card.createPackage(shop, "10'luk", "Paket", 10, "ders"));
        assertThat(paket).contains(Texts.t("tr", "package.none")).doesNotContain("kein Paket");
    }

    @Test
    void anmeldeseite_arabisch_rechtsNachLinks() {
        Card card = Card.create(shop, "قهوة", "x", 10, "قهوة مجانية");
        when(cardService.getById("CARD-1")).thenReturn(card);

        // Ohne Wahl: Deutsch, mit Leiste
        String deutsch = controller.newCustomerPage("CARD-1", null, "ABC234").getBody();
        assertThat(deutsch).contains("<html lang=\"de\" dir=\"ltr\">").contains("placeholder=\"Dein Name\"")
                .contains("/karte-neu/CARD-1?lang=tr&amp;ref=ABC234").contains("const LANG = null;");

        String html = controller.newCustomerPage("CARD-1", "ar", null).getBody();
        assertThat(html).contains("const LANG = \"ar\";");
        assertThat(html).contains("<html lang=\"ar\" dir=\"rtl\">");
        assertThat(html).contains(Texts.t("ar", "signup.headline"));
        assertThat(html).contains("placeholder=\"" + Texts.t("ar", "signup.name") + "\"");
        assertThat(html).doesNotContain("Stempelkarte holen").doesNotContain("Dein Name");
        // Fehlertexte stehen als JS-Zeichenketten bereit
        assertThat(html).contains("const errFields = \"" + Texts.t("ar", "signup.err_fields") + "\"");
    }

    @Test
    void kundeBehaeltSeineSprache_vorDemLaden() {
        when(shop.getLanguageOrDefault()).thenReturn("tr");
        when(customer.getLanguage()).thenReturn("en");
        String html = kartenseite(Card.create(shop, "Kahve", "x", 10, "Free coffee"));
        assertThat(html).contains("lang=\"en\"").contains("1 of 10 stamps");
    }

    @Test
    void deutscherLaden_wieBisher() {
        when(customer.getLanguage()).thenReturn(null);
        String html = kartenseite(Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee"));
        assertThat(html).contains("<html lang=\"de\" dir=\"ltr\">")
                .contains("1 von 10 Stempeln").contains("Noch 9 Stempel bis: Gratis Kaffee")
                .contains("🍎 Zu Apple Wallet hinzufügen").contains("Nächster Schritt");
    }
}
