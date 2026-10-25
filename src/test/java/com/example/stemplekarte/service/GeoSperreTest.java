package com.example.stemplekarte.service;

import com.example.stemplekarte.model.GeoVorfall;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.GeoVorfallRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ohne Ort oder ausserhalb des Ladens wird nicht gestempelt. Vorher lief
 * der Scan durch, wenn man die Ortsabfrage im Browser wegklickte.
 */
class GeoSperreTest {

    private static final double LAT = 52.5163, LON = 13.3777;

    private final GeoVorfallRepository repo = mock(GeoVorfallRepository.class);
    private final GeoPruefung pruefung = new GeoPruefung(repo);

    private Shop laden() {
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.getLatitude()).thenReturn(LAT);
        when(shop.getLongitude()).thenReturn(LON);
        return shop;
    }

    @Test
    void imLadenDarfDurch() {
        assertThat(pruefung.sperre(laden(), "Kasse 1", LAT + 0.0009, LON)).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void ohneOrtWirdGesperrt() {
        assertThat(pruefung.sperre(laden(), "Kasse 1", null, null)).contains("keinen Standort");
        verify(repo).save(any(GeoVorfall.class));
    }

    @Test
    void weitWegWirdGesperrt() {
        // Berliner Dom, gut 1,5 km vom Tor.
        assertThat(pruefung.sperre(laden(), "Kasse 1", 52.5190, 13.4010)).contains("vom Laden entfernt");
        verify(repo).save(any(GeoVorfall.class));
    }

    @Test
    void protokollFehlerHebtSperreNichtAuf() {
        when(repo.save(any())).thenThrow(new RuntimeException("DB weg"));
        assertThat(pruefung.sperre(laden(), "Kasse 1", null, null)).isNotNull();
    }
}
