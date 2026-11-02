package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.GeoVorfall;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.repository.GeoVorfallRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.service.GeoPruefung;
import com.example.stemplekarte.service.RewardService;
import de.brendamour.jpasskit.PKLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Weitere Orte eines Ladens: jeder landet als eigener PKLocation im Pass
 * (mit demselben Text), hoechstens zehn insgesamt. Und beim Scannen gilt
 * "im Laden", wer bei irgendeinem der Orte steht.
 */
class MehrereOrteTest {

    private Shop laden(double lat, double lon) {
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.getName()).thenReturn("Café Mokka");
        when(shop.isLockScreenActive()).thenReturn(true);
        when(shop.getLatitude()).thenReturn(lat);
        when(shop.getLongitude()).thenReturn(lon);
        // Tarif: vom Admin freigeschaltet. 0 waere "Funktion aus".
        when(shop.getMaxOrte()).thenReturn(9);
        return shop;
    }

    @Test
    void tarifBegrenztDieOrte_imPassUndBeimScan() {
        Shop shop = laden(52.52, 13.405);
        when(shop.getMaxOrte()).thenReturn(1);
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        ShopOrtRepository repo = mock(ShopOrtRepository.class);
        when(repo.findByShopOrderByCreatedAtAsc(shop)).thenReturn(List.of(
                ort(shop, "Markt", 52.53, 13.41), ort(shop, "Bahnhof", 48.137, 11.575)));
        var dienst = new ApplePassService(mock(AppProperties.class), mock(PassTemplateGenerator.class),
                mock(RewardService.class), repo);
        // Hauptort + nur der erste weitere.
        assertThat(dienst.sperrbildschirmOrte(shop, card, karte(shop, card))).hasSize(2);
        // Der zweite (Muenchen) zaehlt beim Scannen nicht mehr.
        GeoPruefung pruefung = new GeoPruefung(mock(GeoVorfallRepository.class), repo);
        assertThat(pruefung.sperre(shop, "Kasse 1", 48.1371, 11.5751)).contains("Scan abgelehnt");

        when(shop.getMaxOrte()).thenReturn(0);
        assertThat(dienst.sperrbildschirmOrte(shop, card, karte(shop, card))).hasSize(1);
    }

    private ShopOrt ort(Shop shop, String label, double lat, double lon) {
        return ShopOrt.create(shop, label, lat, lon);
    }

    private CustomerCard karte(Shop shop, Card card) {
        CustomerCard cc = CustomerCard.create(mock(Customer.class), card);
        cc.addStamp();
        return cc;
    }

    @Test
    void hauptortPlusWeitere_gleicherText() {
        Shop shop = laden(52.52, 13.405);
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        ShopOrtRepository repo = mock(ShopOrtRepository.class);
        when(repo.findByShopOrderByCreatedAtAsc(shop)).thenReturn(List.of(
                ort(shop, "Markt", 52.53, 13.41), ort(shop, "Bahnhof", 52.51, 13.40)));
        var dienst = new ApplePassService(mock(AppProperties.class), mock(PassTemplateGenerator.class),
                mock(RewardService.class), repo);

        List<PKLocation> orte = dienst.sperrbildschirmOrte(shop, card, karte(shop, card));
        assertThat(orte).hasSize(3);
        assertThat(orte).extracting(PKLocation::getLatitude).containsExactly(52.52, 52.53, 52.51);
        assertThat(orte).extracting(PKLocation::getRelevantText).containsOnly("Noch 9 Stempel bis: Gratis Kaffee");
    }

    @Test
    void hoechstensZehn() {
        Shop shop = laden(52.52, 13.405);
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        List<ShopOrt> viele = new ArrayList<>();
        for (int i = 0; i < 12; i++) viele.add(ort(shop, "Ort " + i, 52.5 + i * 0.001, 13.4));
        ShopOrtRepository repo = mock(ShopOrtRepository.class);
        when(repo.findByShopOrderByCreatedAtAsc(shop)).thenReturn(viele);
        var dienst = new ApplePassService(mock(AppProperties.class), mock(PassTemplateGenerator.class),
                mock(RewardService.class), repo);
        assertThat(dienst.sperrbildschirmOrte(shop, card, karte(shop, card))).hasSize(10);
    }

    @Test
    void ohneRepository_nurHauptort_ausBleibtAus() {
        Shop shop = laden(52.52, 13.405);
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        var dienst = new ApplePassService(mock(AppProperties.class), mock(PassTemplateGenerator.class),
                mock(RewardService.class));
        assertThat(dienst.sperrbildschirmOrte(shop, card, karte(shop, card))).hasSize(1);
        when(shop.isLockScreenActive()).thenReturn(false);
        assertThat(dienst.sperrbildschirmOrte(shop, card, karte(shop, card))).isEmpty();
    }

    // ── Geo-Pruefung beim Scannen ────────────────────────────────────────

    @Test
    void scanAmZweitenOrt_gehtDurch() {
        Shop shop = laden(52.52, 13.405);
        ShopOrtRepository repo = mock(ShopOrtRepository.class);
        when(repo.findByShopOrderByCreatedAtAsc(shop)).thenReturn(List.of(ort(shop, "Markt", 48.137, 11.575)));
        GeoVorfallRepository vorfaelle = mock(GeoVorfallRepository.class);
        GeoPruefung pruefung = new GeoPruefung(vorfaelle, repo);

        // Muenchen statt Berlin - beim zweiten Ort.
        assertThat(pruefung.sperre(shop, "Kasse 1", 48.1371, 11.5751)).isNull();
        verify(vorfaelle, never()).save(any());
    }

    @Test
    void weitWegVonAllen_meldetDenNaechsten() {
        Shop shop = laden(52.52, 13.405);
        ShopOrtRepository repo = mock(ShopOrtRepository.class);
        when(repo.findByShopOrderByCreatedAtAsc(shop)).thenReturn(List.of(ort(shop, "Markt", 52.53, 13.405)));
        GeoVorfallRepository vorfaelle = mock(GeoVorfallRepository.class);
        GeoPruefung pruefung = new GeoPruefung(vorfaelle, repo);

        // 52.535: naeher am Markt (ca. 550 m) als am Hauptort (ca. 1,7 km).
        String text = pruefung.sperre(shop, "Kasse 1", 52.535, 13.405);
        assertThat(text).contains("Scan abgelehnt");
        long zumMarkt = GeoPruefung.distanzMeter(52.53, 13.405, 52.535, 13.405);
        assertThat(text).contains(String.valueOf(zumMarkt));
        verify(vorfaelle).save(any(GeoVorfall.class));
    }
}
