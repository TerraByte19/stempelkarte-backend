package com.example.stemplekarte.wallet;

import com.example.stemplekarte.controller.LandingController;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.service.RewardService;
import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.passes.PKGenericPass;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kunde ohne Anmeldung hat keinen Namen. Auf der Wallet-Karte und der
 * Kundenseite darf dann kein Feld "Kunde" stehen - weder leer noch mit einer
 * erfundenen Nummer ("Kunde 31232" stand zuerst da und sah nach einem Fehler
 * aus). Mit Namen bleibt alles, wie es war.
 */
class KundeOhneNameTest {

    private Card karte() {
        Shop shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Testladen");
        Card card = Card.create(shop, "Kaffeekarte", "Beschreibung", 10, "Gratis Kaffee");
        card.updateDesign("grid", "preset", "coffee", "#FAC875", "number");
        return card;
    }

    /** Alle Feld-Schluessel und -Werte der Vorderseite, als ein Text. */
    private String felder(Customer kunde) {
        CustomerCard cc = CustomerCard.create(kunde, karte());
        PKGenericPass pass = new ApplePassService(mock(AppProperties.class),
                mock(PassTemplateGenerator.class), mock(RewardService.class))
                .baueFelder(cc.getCard(), cc, true);
        StringBuilder sb = new StringBuilder();
        for (java.util.List<PKField> liste : java.util.Arrays.asList(pass.getHeaderFields(),
                pass.getPrimaryFields(), pass.getSecondaryFields(), pass.getAuxiliaryFields())) {
            if (liste == null) continue;
            for (PKField f : liste) sb.append("key=").append(f.getKey()).append(" wert=").append(f.getValue()).append(';');
        }
        return sb.toString();
    }

    @Test
    void ohneName_keinKundenFeldImPass() {
        String felder = felder(Customer.createOhneMail());

        assertThat(felder).contains("key=stamps").contains("key=reward");
        assertThat(felder).doesNotContain("key=name");
    }

    @Test
    void mitName_bleibtDasKundenFeld() {
        String felder = felder(Customer.create("Anna", "anna@example.com"));

        assertThat(felder).contains("key=name").contains("Anna");
    }

    @Test
    void altGespeicherterPlatzhalterNameWirdNichtMehrGezeigt() {
        Customer alt = Customer.createOhneMail();
        ReflectionTestUtils.setField(alt, "name", "Kunde 31232");

        assertThat(alt.getName()).isEmpty();
        assertThat(felder(alt)).doesNotContain("31232");
    }

    @Test
    void kundenseite_ohneLeereNamenszeile() {
        String mit = "<body><div class=\"customer-name\">👤 Anna</div></body>";
        String ohne = "<body><div class=\"customer-name\">👤 </div>"
                + "<div class=\"zeile\"><span></span><b></b></div></body>";

        assertThat(ReflectionTestUtils.<String>invokeMethod(LandingController.class, "mitKontrast", mit, "#3C3489"))
                .contains("👤 Anna");
        assertThat(ReflectionTestUtils.<String>invokeMethod(LandingController.class, "mitKontrast", ohne, "#3C3489"))
                .doesNotContain("customer-name").doesNotContain("zeile");
    }
}
