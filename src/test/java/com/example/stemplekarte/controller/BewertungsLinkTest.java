package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.service.ShopService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Link zur Google-Bewertung: vom Laden eingetragen, auf Seiten fremder
 * Kunden als href ausgegeben. Deshalb zwei Tueren: beim Speichern nur
 * https ohne Leerzeichen, bei der Ausgabe escaped.
 */
class BewertungsLinkTest {

    private ShopService dienst(Shop shop) {
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.findById("SHOP-1")).thenReturn(Optional.of(shop));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ShopService(repo, null, null, null);
    }

    private Shop laden() {
        return Shop.create("a@b.de", "hash", "Café Mokka", 3);
    }

    @Test
    void ohneLink_nichts() {
        assertThat(LandingController.bewertungsLink(null)).isEmpty();
        assertThat(LandingController.bewertungsLink(laden())).isEmpty();
    }

    @Test
    void mitLink_escapedImAttribut() {
        Shop shop = laden();
        shop.updateGoogleReviewUrl("https://g.page/r/abc'><script>");
        String html = LandingController.bewertungsLink(shop);
        assertThat(html).contains("href='https://g.page/r/abc&#39;&gt;&lt;script&gt;'")
                .contains("Bewerte uns bei Google")
                .contains("target='_blank'")
                .doesNotContain("<script>");
    }

    @Test
    void speichern_nurHttps() {
        Shop shop = laden();
        ShopService dienst = dienst(shop);
        assertThatThrownBy(() -> dienst.updateGoogleReviewUrl("SHOP-1", "javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> dienst.updateGoogleReviewUrl("SHOP-1", "http://g.page/r/abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.updateGoogleReviewUrl("SHOP-1", "https://g.page/r/a b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Leerzeichen");
        assertThatThrownBy(() -> dienst.updateGoogleReviewUrl("SHOP-1", "https://" + "x".repeat(500)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
        assertThat(shop.getGoogleReviewUrl()).isNull();
    }

    @Test
    void speichern_trimmt_leerEntfernt() {
        Shop shop = laden();
        ShopService dienst = dienst(shop);
        dienst.updateGoogleReviewUrl("SHOP-1", "  https://g.page/r/abc/review  ");
        assertThat(shop.getGoogleReviewUrl()).isEqualTo("https://g.page/r/abc/review");
        dienst.updateGoogleReviewUrl("SHOP-1", " ");
        assertThat(shop.getGoogleReviewUrl()).isNull();
        dienst.updateGoogleReviewUrl("SHOP-1", null);
        assertThat(shop.getGoogleReviewUrl()).isNull();
    }
}
