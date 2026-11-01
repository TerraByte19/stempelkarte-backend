package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.RewardService;
import de.brendamour.jpasskit.PKField;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Der Apple-Pass einer gesperrten Karte und der Moment des Freischaltens.
 *
 * Drei Dinge muessen stimmen:
 * - gesperrt: Geschenk sichtbar, keine Stempel, kein Streifen;
 * - der Kopf traegt im gesperrten und im offenen Pass denselben Key, sonst
 *   zeigt iOS beim Freischalten keine Mitteilung;
 * - eine Karte ohne Willkommensgeschenk bekommt exakt den Pass von vorher.
 */
class WillkommenPassTest {

    private final Shop shop = mock(Shop.class);

    private ApplePassService dienst() {
        return new ApplePassService(mock(AppProperties.class),
                mock(PassTemplateGenerator.class), mock(RewardService.class));
    }

    private Card karte() {
        return Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
    }

    private CustomerCard kundenkarte(Card card) {
        Customer customer = mock(Customer.class);
        when(customer.getName()).thenReturn("Ana");
        return CustomerCard.create(customer, card);
    }

    private CustomerCard gesperrt(Card card) {
        CustomerCard cc = kundenkarte(card);
        cc.willkommenVormerken("Erster Kaffee geht auf uns");
        return cc;
    }

    @Test
    void gesperrt_standard_keineTexte() {
        // Ohne Haken nur Bild, Logo und Name. Der Kopf bleibt leer stehen,
        // damit das Freischalten eine Mitteilung bringt.
        Card card = karte();
        var pass = dienst().baueFelder(card, gesperrt(card), false);

        PKField kopf = pass.getHeaderFields().get(0);
        assertThat(kopf.getKey()).isEqualTo("stamps-header");
        assertThat(kopf.getValue()).isEqualTo("");
        assertThat(pass.getPrimaryFields()).isNullOrEmpty();
        assertThat(pass.getSecondaryFields()).isNullOrEmpty();
        assertThat(pass.getAuxiliaryFields().get(0).getValue()).isEqualTo("Ana");
    }

    @Test
    void gesperrt_nurHinweis() {
        Card card = karte();
        card.updateWillkommenSichtbar(false, false, true);
        var pass = dienst().baueFelder(card, gesperrt(card), false);

        assertThat(pass.getHeaderFields().get(0).getValue()).isEqualTo("");
        assertThat(pass.getSecondaryFields()).extracting(PKField::getKey).containsExactly("welcome-how");
    }

    @Test
    void gesperrt_zeigtGeschenk_keineStempel() {
        Card card = karte();
        card.updateWillkommenSichtbar(true, true, true);
        var pass = dienst().baueFelder(card, gesperrt(card), false);

        PKField kopf = pass.getHeaderFields().get(0);
        assertThat(kopf.getKey()).isEqualTo("stamps-header");
        assertThat(kopf.getLabel()).isNull();
        assertThat(kopf.getValue()).isEqualTo("Willkommen");

        // Kein grosses Feld mehr: das Geschenk steht klein in der Zeile darunter.
        assertThat(pass.getPrimaryFields()).isNullOrEmpty();
        assertThat(pass.getSecondaryFields()).hasSize(2);
        assertThat(pass.getSecondaryFields().get(0).getKey()).isEqualTo("welcome");
        assertThat(pass.getSecondaryFields().get(0).getLabel()).isEqualTo("🎁 GESCHENK");
        assertThat(pass.getSecondaryFields().get(0).getValue()).isEqualTo("Erster Kaffee geht auf uns");
        assertThat(pass.getSecondaryFields().get(1).getKey()).isEqualTo("welcome-how");
        assertThat(pass.getSecondaryFields().get(1).getValue()).isEqualTo("An der Kasse vorzeigen");
        assertThat(pass.getAuxiliaryFields().get(0).getValue()).isEqualTo("Ana");
        // Nichts, was nach Stempeln aussieht.
        assertThat(pass.getHeaderFields()).extracting(f -> String.valueOf(f.getValue()))
                .noneMatch(v -> v.contains("/10"));
    }

    @Test
    void gesperrt_eigenerTitelUndHinweis() {
        Card card = karte();
        card.updateWillkommenDesign("Hallo!", "Gilt bis Monatsende", null, null, null, null);
        card.updateWillkommenSichtbar(true, true, true);
        var pass = dienst().baueFelder(card, gesperrt(card), false);

        assertThat(pass.getHeaderFields().get(0).getValue()).isEqualTo("Hallo!");
        assertThat(pass.getSecondaryFields().get(1).getValue()).isEqualTo("Gilt bis Monatsende");
    }

    @Test
    void farben_nurImGesperrtenZustand() {
        when(shop.getColorBackground()).thenReturn("#000000");
        Card card = karte();
        card.updateColors("#3C3489", "#FFFFFF", "#FAC875");
        card.updateWillkommenDesign(null, null, null, "#112233", null, "#AABBCC");

        CustomerCard gesperrtKarte = gesperrt(card);
        assertThat(PassTemplateGenerator.passFarben(card, shop, gesperrtKarte))
                .containsExactly("#112233", "#FFFFFF", "#AABBCC");

        gesperrtKarte.willkommenEinloesen(Instant.now());
        assertThat(PassTemplateGenerator.passFarben(card, shop, gesperrtKarte))
                .containsExactly("#3C3489", "#FFFFFF", "#FAC875");
        assertThat(PassTemplateGenerator.passFarben(card, shop, kundenkarte(card)))
                .containsExactly("#3C3489", "#FFFFFF", "#FAC875");
    }

    @Test
    void willkommensBild_nurGesperrtUndMitBild() {
        Card card = karte();
        assertThat(PassTemplateGenerator.brauchtWillkommensBild(gesperrt(card))).isFalse();

        card.updateWillkommenDesign(null, null, "https://res.cloudinary.com/x/w.png", null, null, null);
        CustomerCard cc = gesperrt(card);
        assertThat(PassTemplateGenerator.brauchtWillkommensBild(cc)).isTrue();
        assertThat(PassTemplateGenerator.brauchtWillkommensBild(kundenkarte(card))).isFalse();

        cc.willkommenEinloesen(Instant.now());
        assertThat(PassTemplateGenerator.brauchtWillkommensBild(cc)).isFalse();
    }

    @Test
    void gesperrt_rasterStil_kopfKeyWieImRaster() {
        Card card = karte();
        var gesperrtPass = dienst().baueFelder(card, gesperrt(card), true);
        var offenPass = dienst().baueFelder(card, kundenkarte(card), true);
        assertThat(gesperrtPass.getHeaderFields().get(0).getKey())
                .isEqualTo(offenPass.getHeaderFields().get(0).getKey())
                .isEqualTo("stamps");
    }

    @Test
    void freischalten_meldetSich() {
        Card card = karte();
        CustomerCard cc = gesperrt(card);
        String keyVorher = dienst().baueFelder(card, cc, false).getHeaderFields().get(0).getKey();

        cc.willkommenEinloesen(Instant.now());
        var pass = dienst().baueFelder(card, cc, false);

        PKField kopf = pass.getHeaderFields().get(0);
        assertThat(kopf.getKey()).isEqualTo(keyVorher);
        assertThat(kopf.getValue()).isEqualTo("0/10");
        assertThat(kopf.getChangeMessage()).isEqualTo("🎉 Karte freigeschaltet! Stempel: %@");
    }

    @Test
    void ersterStempelDanach_normaleMeldung() {
        Card card = karte();
        CustomerCard cc = gesperrt(card);
        cc.willkommenEinloesen(Instant.now().minusSeconds(60));
        cc.addStamp();

        var kopf = dienst().baueFelder(card, cc, false).getHeaderFields().get(0);
        assertThat(kopf.getValue()).isEqualTo("1/10");
        assertThat(kopf.getChangeMessage()).isEqualTo("Update! Dein Stempelstand: %@");
    }

    @Test
    void ohneGeschenk_passWieBisher() {
        Card card = karte();
        card.updateWillkommen(true, null); // Angebot an aendert bestehende Karten nicht
        CustomerCard cc = kundenkarte(card);
        cc.addStamp();

        var pass = dienst().baueFelder(card, cc, false);
        PKField kopf = pass.getHeaderFields().get(0);
        assertThat(kopf.getLabel()).isEqualTo("STEMPEL");
        assertThat(kopf.getValue()).isEqualTo("1/10");
        assertThat(kopf.getChangeMessage()).isEqualTo("Update! Dein Stempelstand: %@");
        assertThat(pass.getPrimaryFields().get(0).getKey()).isEqualTo("stamps-big");
    }

    @Test
    void sperrbildschirm_erinnertAnsGeschenk() {
        Shop laden = mock(Shop.class);
        when(laden.isLockScreenActive()).thenReturn(true);
        when(laden.getLatitude()).thenReturn(52.5);
        when(laden.getLongitude()).thenReturn(13.4);
        Card card = Card.create(laden, "Kaffee", "x", 10, "Gratis Kaffee");

        var orte = dienst().sperrbildschirmOrte(laden, card, gesperrt(card));
        assertThat(orte).hasSize(1);
        assertThat(orte.get(0).getRelevantText()).isEqualTo("Erster Kaffee geht auf uns - wartet auf dich");
    }

    @Test
    void streifen_nurOhneSperre() {
        Card card = karte();
        assertThat(PassTemplateGenerator.brauchtStreifen("grid", gesperrt(card))).isFalse();
        assertThat(PassTemplateGenerator.brauchtStreifen("grid", kundenkarte(card))).isTrue();
        assertThat(PassTemplateGenerator.brauchtStreifen("number", kundenkarte(card))).isFalse();
    }
}
