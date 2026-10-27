package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Eigene Farbe fuer den Ladennamen im Apple-Pass. Apple faerbt logoText
 * immer in der Textfarbe der Karte - mit eigener Farbe steckt der Name
 * deshalb im Logo-Bild, und logoText muss weg, sonst steht er doppelt da.
 */
class NamensFarbeTest {

    private Shop laden(String farbe) {
        Shop shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Café Mokka");
        when(shop.getNameColor()).thenReturn(farbe);
        return shop;
    }

    @Test
    void ohneFarbe_bleibtLogoText() {
        assertThat(ApplePassService.logoTextFuer(laden(null))).isEqualTo("Café Mokka");
        assertThat(PassTemplateGenerator.eigeneNamensFarbe(laden(null))).isNull();
    }

    @Test
    void mitFarbe_keinLogoText() {
        assertThat(ApplePassService.logoTextFuer(laden("#FF8800"))).isNull();
        assertThat(PassTemplateGenerator.eigeneNamensFarbe(laden("#FF8800")))
                .isEqualTo(new Color(0xFF8800));
    }

    @Test
    void kaputteFarbe_wieOhne() {
        assertThat(PassTemplateGenerator.eigeneNamensFarbe(laden("red;}"))).isNull();
        assertThat(ApplePassService.logoTextFuer(laden("red;}"))).isEqualTo("Café Mokka");
    }

    @Test
    void bild_haeltApplesGrenzenUndTraegtDieFarbe() {
        Color orange = new Color(0xFF8800);
        BufferedImage logo = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        BufferedImage img = PassTemplateGenerator.logoMitName(logo, "Café Mokka", orange, 320, 100);

        assertThat(img.getWidth()).isLessThanOrEqualTo(320);
        assertThat(img.getHeight()).isEqualTo(100);
        assertThat(enthaelt(img, orange)).isTrue();
    }

    @Test
    void langerName_wirdGekuerztStattUeberzulaufen() {
        BufferedImage img = PassTemplateGenerator.logoMitName(null,
                "Bäckerei und Konditorei Zum Goldenen Hirschen", Color.RED, 160, 50);
        assertThat(img.getWidth()).isLessThanOrEqualTo(160);
    }

    private static boolean enthaelt(BufferedImage img, Color farbe) {
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) == 0xFF && (argb & 0xFFFFFF) == (farbe.getRGB() & 0xFFFFFF)) return true;
            }
        }
        return false;
    }
}
