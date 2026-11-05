package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.i18n.Texts;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.EmpfehlungService;
import com.example.stemplekarte.service.PaketService;
import com.example.stemplekarte.service.RewardService;
import com.example.stemplekarte.service.Stufen;
import de.brendamour.jpasskit.passes.PKGenericPass;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wallet-Karte, Mitteilungen und Hilfstexte in der Sprache des Kunden.
 * Deutsch bleibt Zeichen fuer Zeichen wie vorher.
 */
class WalletSprachenTest {

    private ApplePassService dienst() {
        return new ApplePassService(mock(AppProperties.class),
                mock(PassTemplateGenerator.class), mock(RewardService.class));
    }

    private Shop laden(String sprache) {
        Shop shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Kahve Köşesi");
        when(shop.getLanguageOrDefault()).thenReturn(sprache);
        return shop;
    }

    private Customer kunde(String sprache) {
        Customer c = mock(Customer.class);
        when(c.getName()).thenReturn("Ayşe");
        when(c.getLanguage()).thenReturn(sprache);
        return c;
    }

    @Test
    void stempelkarte_tuerkisch_labelsUndMitteilung() {
        Card card = Card.create(laden("tr"), "Kahve", "x", 10, "Bir kahve bedava");
        CustomerCard cc = CustomerCard.create(kunde("tr"), card);
        cc.addStamp();

        PKGenericPass pass = dienst().baueFelder(card, cc, false);
        assertThat(pass.getHeaderFields().get(0).getLabel()).isEqualTo(Texts.t("tr", "pass.stamps_label"))
                .isNotEqualTo("STEMPEL");
        assertThat(pass.getHeaderFields().get(0).getChangeMessage())
                .isEqualTo(Texts.t("tr", "pass.msg_update")).contains("%@");
        assertThat(pass.getPrimaryFields().get(0).getLabel()).isEqualTo(Texts.t("tr", "pass.countdown_label"));
        assertThat(pass.getAuxiliaryFields().get(0).getLabel()).isEqualTo(Texts.t("tr", "pass.customer_label"));
    }

    @Test
    void kundeBehaeltSeineSprache() {
        Card card = Card.create(laden("tr"), "Kahve", "x", 10, "Free coffee");
        CustomerCard cc = CustomerCard.create(kunde("en"), card);
        PKGenericPass pass = dienst().baueFelder(card, cc, true);
        assertThat(pass.getHeaderFields().get(0).getLabel()).isEqualTo("STAMPS");
        assertThat(pass.getSecondaryFields().get(0).getLabel()).isEqualTo("REWARD");
    }

    @Test
    void deutsch_wieBisher_auchBeiTuerkischemLaden() {
        Card card = Card.create(laden("tr"), "Kaffee", "x", 10, "Gratis Kaffee");
        CustomerCard cc = CustomerCard.create(kunde(null), card);
        for (int i = 0; i < 10; i++) cc.addStamp();
        PKGenericPass pass = dienst().baueFelder(card, cc, false);
        assertThat(pass.getHeaderFields().get(0).getLabel()).isEqualTo("STEMPEL");
        assertThat(pass.getHeaderFields().get(0).getChangeMessage()).isEqualTo("🎉 Gratis Kaffee verdient! Neue Karte: %@");
        assertThat(pass.getPrimaryFields().get(0).getLabel()).isEqualTo("BELOHNUNG");
        assertThat(pass.getPrimaryFields().get(0).getValue()).isEqualTo("Bereit! 🎉");
        assertThat(pass.getSecondaryFields().get(0).getValue()).isEqualTo("Gratis Kaffee verfügbar!");
        assertThat(pass.getAuxiliaryFields().get(0).getLabel()).isEqualTo("KUNDE");
    }

    @Test
    void mitgliedUndPaket_arabisch() {
        Shop shop = laden("ar");
        Card mitglied = Card.createMember(shop, "Üye", "x");
        PKGenericPass m = dienst().baueFelder(mitglied, CustomerCard.create(kunde("ar"), mitglied), false);
        assertThat(m.getHeaderFields().get(0).getLabel()).isEqualTo(Texts.t("ar", "pass.visits_label"))
                .isNotEqualTo("BESUCHE");

        Card paket = Card.createPackage(shop, "10er", "x", 10, "Kurse");
        CustomerCard cc = CustomerCard.create(kunde("ar"), paket);
        PKGenericPass p = dienst().baueFelder(paket, cc, false);
        assertThat(p.getHeaderFields().get(0).getLabel()).isEqualTo(Texts.t("ar", "pass.left_label"));
        assertThat(p.getHeaderFields().get(0).getChangeMessage()).contains("%@");
        assertThat(p.getPrimaryFields().get(0).getValue()).isEqualTo(Texts.t("ar", "pass.package_empty"));
    }

    @Test
    void googleMitteilung_tuerkisch() {
        Card card = Card.create(laden("tr"), "Kahve", "x", 8, "Bir kahve bedava");
        CustomerCard cc = CustomerCard.create(kunde("tr"), card);
        cc.addStamp();
        String[] text = GoogleWalletService.meldungText(cc, GoogleWalletService.Meldung.STEMPEL);
        assertThat(text[0]).isEqualTo(Texts.t("tr", "gw.stamp_header", "Kahve Köşesi")).doesNotContain("Neuer Stempel");
        assertThat(text[1]).isEqualTo(Texts.t("tr", "gw.stamp_body", 1, 8));
    }

    @Test
    void hilfstexte_inAllenSprachen() {
        for (String lang : Texts.SPRACHEN) {
            Card card = Card.create(laden(lang), "K", "x", 10, "R");
            card.updateEmpfehlung(true, 2);
            assertThat(EmpfehlungService.werbeText(lang, card)).contains("2").isNotBlank();
            Card paket = Card.createPackage(laden(lang), "P", "x", 10, "Kurse");
            CustomerCard cc = CustomerCard.create(kunde(null), paket);
            assertThat(PaketService.restText(lang, paket, cc)).isNotBlank();
            cc.paketAufladen(10, java.time.Instant.now());
            assertThat(PaketService.restText(lang, paket, cc)).contains("10").contains("Kurse");
        }
        // Deutsch unveraendert
        Card paket = Card.createPackage(laden(null), "P", "x", 10, "Kurse");
        CustomerCard cc = CustomerCard.create(kunde(null), paket);
        assertThat(PaketService.restText(paket, cc)).isEqualTo("Paket leer");
    }
}
