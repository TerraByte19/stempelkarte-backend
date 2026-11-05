package com.example.stemplekarte.service;

import com.example.stemplekarte.i18n.Texts;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Rueckhol-Standardtext in der Sprache des Kunden; Deutsch wie vorher. */
class RueckholSprachenTest {

    private Shop laden(String sprache) {
        Shop shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Kahve Köşesi");
        when(shop.getLanguageOrDefault()).thenReturn(sprache);
        return shop;
    }

    private CustomerCard karte(Shop shop, int stempel) {
        return karte(shop, stempel, null);
    }

    private CustomerCard karte(Shop shop, int stempel, String sprache) {
        Card card = Card.create(shop, "Kahve", "x", 10, "Bir kahve bedava");
        Customer kunde = mock(Customer.class);
        when(kunde.getLanguage()).thenReturn(sprache);
        CustomerCard cc = CustomerCard.create(kunde, card);
        for (int i = 0; i < stempel; i++) cc.addStamp();
        return cc;
    }

    @Test
    void tuerkisch() {
        Shop shop = laden("tr");
        String text = RueckholService.text(shop, karte(shop, 7, "tr"), mock(RewardService.class));
        assertThat(text).isEqualTo(Texts.t("tr", "winback.progress", 3, Texts.t("tr", "unit.stamps"), "Bir kahve bedava"))
                .doesNotContain("Wir vermissen dich");
        String voll = RueckholService.text(shop, karte(shop, 10, "tr"), mock(RewardService.class));
        assertThat(voll).isEqualTo(Texts.t("tr", "winback.ready", "Bir kahve bedava"));
    }

    @Test
    void deutsch_wieBisher() {
        Shop shop = laden("tr");
        assertThat(RueckholService.text(shop, karte(shop, 7), mock(RewardService.class)))
                .isEqualTo("Wir vermissen dich! Noch 3 Stempel bis: Bir kahve bedava");
    }
}
