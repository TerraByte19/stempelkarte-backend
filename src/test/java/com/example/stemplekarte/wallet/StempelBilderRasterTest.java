package com.example.stemplekarte.wallet;

import com.example.stemplekarte.service.RewardService;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Mehrere eigene Stempel-Bilder im Raster: sie wechseln sich der Reihe nach
 * ab und fangen danach wieder von vorn an.
 *
 * Geprueft wird am fertigen Bild - die Farbe in der Mitte jedes Feldes.
 */
class StempelBilderRasterTest {

    private final PassTemplateGenerator gen = new PassTemplateGenerator(mock(RewardService.class));

    private static final int ROT = 0xFFFF0000;
    private static final int BLAU = 0xFF0000FF;

    private static BufferedImage flaeche(Color farbe) {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(farbe);
        g.fillRect(0, 0, 40, 40);
        g.dispose();
        return img;
    }

    /** Mitte von Feld i in einem einreihigen Raster (bis 5 Felder), wie renderStrip rechnet. */
    private static int mitteX(int i, int felder, int w) {
        double padX = w * 0.05;
        double cellW = (w - 2 * padX) / felder;
        return (int) Math.round(padX + i * cellW + cellW / 2);
    }

    @Test
    void zweiBilderWechselnSichAbUndWiederholenSich() {
        BufferedImage strip = gen.renderStrip(4, 4, "#6F4E37", "upload", "coffee", "faded",
                375, 144, List.of(flaeche(Color.RED), flaeche(Color.BLUE)));

        assertThat(strip.getRGB(mitteX(0, 4, 375), 72)).isEqualTo(ROT);
        assertThat(strip.getRGB(mitteX(1, 4, 375), 72)).isEqualTo(BLAU);
        assertThat(strip.getRGB(mitteX(2, 4, 375), 72)).isEqualTo(ROT);
        assertThat(strip.getRGB(mitteX(3, 4, 375), 72)).isEqualTo(BLAU);
    }

    @Test
    void einBildFuelltAlleFelderWieBisher() {
        BufferedImage strip = gen.renderStrip(3, 3, "#6F4E37", "upload", "coffee", "faded",
                375, 144, List.of(flaeche(Color.RED)));

        for (int i = 0; i < 3; i++) {
            assertThat(strip.getRGB(mitteX(i, 3, 375), 72)).as("Feld " + (i + 1)).isEqualTo(ROT);
        }
    }

    /** Laesst sich kein Bild laden, zeichnet das Raster das eingebaute Symbol - kein Absturz. */
    @Test
    void ohneGeladeneBilderKommtDasEingebauteSymbol() {
        BufferedImage leer = gen.renderStrip(2, 4, "#6F4E37", "upload", "dot", "number",
                375, 144, List.of());
        BufferedImage ohne = gen.renderStrip(2, 4, "#6F4E37", "upload", "dot", "number",
                375, 144, null);

        // Gefuelltes Feld: Punkt in Stempelfarbe auf weisser Scheibe.
        int erwartet = new Color(0x6F, 0x4E, 0x37).getRGB();
        assertThat(leer.getRGB(mitteX(0, 4, 375), 72)).isEqualTo(erwartet);
        assertThat(ohne.getRGB(mitteX(0, 4, 375), 72)).isEqualTo(erwartet);
    }

    /** Typ "preset" ignoriert hochgeladene Bilder, auch wenn noch welche an der Karte haengen. */
    @Test
    void eingebautesSymbolGewinntWennDerTypNichtUploadIst() {
        BufferedImage strip = gen.renderStrip(1, 2, "#6F4E37", "preset", "dot", "number",
                375, 144, List.of(flaeche(Color.RED)));

        assertThat(strip.getRGB(mitteX(0, 2, 375), 72)).isNotEqualTo(ROT);
    }

    @Test
    void mehrBilderAlsFelderNutztNurDieErsten() {
        BufferedImage strip = gen.renderStrip(2, 2, "#6F4E37", "upload", "coffee", "faded",
                375, 144, List.of(flaeche(Color.RED), flaeche(Color.BLUE), flaeche(Color.GREEN)));

        assertThat(strip.getRGB(mitteX(0, 2, 375), 72)).isEqualTo(ROT);
        assertThat(strip.getRGB(mitteX(1, 2, 375), 72)).isEqualTo(BLAU);
    }
}
