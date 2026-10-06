package com.example.stemplekarte.service;

import com.example.stemplekarte.model.GeoVorfall;
import org.junit.jupiter.api.Test;

import static com.example.stemplekarte.service.GeoPruefung.RADIUS_METER;
import static com.example.stemplekarte.service.GeoPruefung.distanzMeter;
import static com.example.stemplekarte.service.GeoPruefung.pruefe;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft, ob ein Scan dort passiert ist, wo der Laden steht.
 *
 * Zwei Dinge gehen hier leicht schief und fallen im Betrieb kaum auf:
 * eine Entfernungsformel, die um Faktor 1000 danebenliegt (Meter gegen
 * Kilometer), und ein Laden ohne hinterlegte Koordinaten, der dann bei
 * jedem Scan einen Vorfall erzeugt.
 */
class GeoPruefungTest {

    // Brandenburger Tor und Berliner Dom, rund 2,2 km Luftlinie.
    private static final double TOR_LAT = 52.5163, TOR_LON = 13.3777;
    private static final double DOM_LAT = 52.5190, DOM_LON = 13.4010;

    @Test
    void entfernungStimmtInMetern() {
        long d = distanzMeter(TOR_LAT, TOR_LON, DOM_LAT, DOM_LON);
        assertThat(d).isBetween(1_500L, 2_500L);
    }

    @Test
    void derselbePunktIstNullMeter() {
        assertThat(distanzMeter(TOR_LAT, TOR_LON, TOR_LAT, TOR_LON)).isZero();
    }

    /** Ein paar Meter daneben bleiben ein paar Meter - keine Kilometer. */
    @Test
    void kleineAbstaendeBleibenKlein() {
        long d = distanzMeter(TOR_LAT, TOR_LON, TOR_LAT + 0.0009, TOR_LON);
        assertThat(d).isBetween(80L, 120L);
    }

    @Test
    void scanAmLadenIstInOrdnung() {
        assertThat(pruefe(TOR_LAT, TOR_LON, TOR_LAT + 0.0009, TOR_LON, RADIUS_METER)).isNull();
    }

    @Test
    void scanWeitWegFaelltAuf() {
        assertThat(pruefe(TOR_LAT, TOR_LON, DOM_LAT, DOM_LON, RADIUS_METER))
                .isEqualTo(GeoVorfall.Art.WEIT_WEG);
    }

    @Test
    void genauAmRandGiltNochAlsImLaden() {
        // Der Radius selbst zaehlt als drinnen, erst darueber faellt es auf.
        assertThat(pruefe(0.0, 0.0, 0.0, 0.0, 0)).isNull();
    }

    @Test
    void ohneOrtVomGeraetEigeneArt() {
        assertThat(pruefe(TOR_LAT, TOR_LON, null, null, RADIUS_METER))
                .isEqualTo(GeoVorfall.Art.OHNE_ORT);
        assertThat(pruefe(TOR_LAT, TOR_LON, TOR_LAT, null, RADIUS_METER))
                .isEqualTo(GeoVorfall.Art.OHNE_ORT);
    }

    /**
     * Ohne Koordinaten am Laden ist die Pruefung aus. Sonst erzeugte jeder
     * Laden ohne Adresse bei jedem Scan einen Vorfall, und die Liste waere
     * nach einem Tag unbrauchbar.
     */
    @Test
    void ohneLadenortPasstNichts() {
        assertThat(pruefe(null, null, TOR_LAT, TOR_LON, RADIUS_METER)).isNull();
        assertThat(pruefe(null, null, null, null, RADIUS_METER)).isNull();
        assertThat(pruefe(TOR_LAT, null, DOM_LAT, DOM_LON, RADIUS_METER)).isNull();
    }
}
