package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ohne Ladenort kein Scannen - aber nicht von einer Sekunde auf die andere.
 *
 * Diese Regel schaltet im laufenden Betrieb Kassen ab. Faellt sie zu frueh,
 * steht ein Laden mit Kunden davor und kann nichts tun, weil nur der
 * Inhaber den Ort setzen kann. Deshalb haengt jeder Fall an einem Test.
 */
class StandortPflichtTest {

    private Shop laden(Double lat, Double lon, Instant angelegt) {
        Shop shop = mock(Shop.class);
        when(shop.getLatitude()).thenReturn(lat);
        when(shop.getLongitude()).thenReturn(lon);
        when(shop.getCreatedAt()).thenReturn(angelegt);
        return shop;
    }

    private final Instant vorher = StandortPflicht.PFLICHT_AB.minusSeconds(86_400);
    private final Instant nachher = StandortPflicht.PFLICHT_AB.plusSeconds(86_400);

    @Test
    void mitStandortDarfJederScannen() {
        assertThat(StandortPflicht.darfScannen(laden(52.5, 13.4, vorher), Instant.now())).isTrue();
        assertThat(StandortPflicht.darfScannen(laden(52.5, 13.4, nachher), Instant.now())).isTrue();
    }

    @Test
    void halberStandortZaehltNicht() {
        // Nur Breite, keine Laenge: damit laesst sich keine Entfernung rechnen.
        assertThat(StandortPflicht.hatStandort(laden(52.5, null, vorher))).isFalse();
        assertThat(StandortPflicht.hatStandort(laden(null, 13.4, vorher))).isFalse();
    }

    @Test
    void neuerLadenOhneStandortDarfNichtScannen() {
        assertThat(StandortPflicht.darfScannen(laden(null, null, nachher), nachher)).isFalse();
    }

    @Test
    void bestehenderLadenDarfBisZumFristendeWeiter() {
        Shop alt = laden(null, null, vorher);
        Instant kurzVorSchluss = StandortPflicht.fristEnde().minusSeconds(60);

        assertThat(StandortPflicht.darfScannen(alt, StandortPflicht.PFLICHT_AB)).isTrue();
        assertThat(StandortPflicht.darfScannen(alt, kurzVorSchluss)).isTrue();
    }

    @Test
    void nachDerFristIstAuchFuerBestehendeSchluss() {
        Shop alt = laden(null, null, vorher);
        assertThat(StandortPflicht.darfScannen(alt, StandortPflicht.fristEnde())).isFalse();
        assertThat(StandortPflicht.darfScannen(alt, StandortPflicht.fristEnde().plusSeconds(60))).isFalse();
    }

    /**
     * Alte Zeilen koennen ohne Anlagedatum in der Datenbank stehen. Als "neu"
     * gewertet waeren sie sofort gesperrt - ohne Frist, ohne Vorwarnung.
     */
    @Test
    void ohneAnlagedatumGiltDerLadenAlsBestehend() {
        Shop ohneDatum = laden(null, null, null);
        assertThat(StandortPflicht.istNeu(ohneDatum)).isFalse();
        assertThat(StandortPflicht.darfScannen(ohneDatum, StandortPflicht.PFLICHT_AB)).isTrue();
    }

    @Test
    void fristIstVierzehnTage() {
        assertThat(StandortPflicht.fristEnde())
                .isEqualTo(StandortPflicht.PFLICHT_AB.plus(java.time.Duration.ofDays(14)));
    }
}
