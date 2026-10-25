package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Radius der Sperrbildschirm-Erinnerung: 50, 75 oder 100 m, sonst Standard. */
class SperrbildschirmRadiusTest {

    private Shop ladenMit(Integer radius) {
        Shop shop = mock(Shop.class);
        when(shop.getLockScreenRadiusMeter()).thenReturn(radius);
        return shop;
    }

    @Test
    void gewaehlterRadiusGilt() {
        assertThat(ApplePassService.radiusFuer(ladenMit(50))).isEqualTo(50);
        assertThat(ApplePassService.radiusFuer(ladenMit(75))).isEqualTo(75);
    }

    @Test
    void ohneWahlGiltStandard() {
        assertThat(ApplePassService.radiusFuer(ladenMit(null))).isEqualTo(100);
    }

    /** iOS deckelt ohnehin bei rund 100 m - groessere Werte werden nicht uebernommen. */
    @Test
    void unbekannterRadiusFaelltAufStandard() {
        assertThat(ApplePassService.radiusFuer(ladenMit(250))).isEqualTo(100);
    }
}
