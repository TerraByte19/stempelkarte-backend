package com.example.stemplekarte.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Aufkleber: A4-Bogen in 300 dpi, einzelner Aufkleber, Texte begrenzt. */
class AufkleberTest {

    private final AufkleberService dienst = new AufkleberService();

    @Test
    void texte_standardUndGrenzen() {
        AufkleberService.Texte t = AufkleberService.texte(" ", null, " Café  Nord ", "Kaffee\n");
        assertThat(t.titel()).isEqualTo(AufkleberService.TITEL_STANDARD);
        assertThat(t.zeile()).isEqualTo(AufkleberService.ZEILE_STANDARD);
        assertThat(t.laden()).isEqualTo("Café Nord");
        assertThat(t.karte()).isEqualTo("Kaffee");
        assertThatThrownBy(() -> AufkleberService.texte("x".repeat(31), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AufkleberService.texte(null, "x".repeat(71), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AufkleberService.Groesse.von(" gross ")).isEqualTo(AufkleberService.Groesse.GROSS);
        assertThat(AufkleberService.Groesse.von("")).isEqualTo(AufkleberService.Groesse.KLEIN);
        assertThatThrownBy(() -> AufkleberService.Groesse.von("riesig")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AufkleberService.Groesse.KLEIN.anzahl()).isEqualTo(24);
        assertThat(AufkleberService.Groesse.GROSS.anzahl()).isEqualTo(6);
    }

    @Test
    void bogen_a4_300dpi_nichtLeer() throws Exception {
        AufkleberService.Texte t = AufkleberService.texte("Stempel sammeln!", null, "Café Nord", "Kaffee");
        byte[] png = dienst.bogen("https://stampit.test/karte-neu/CARD-1", t, AufkleberService.Groesse.KLEIN);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(img.getWidth()).isEqualTo(2480);
        assertThat(img.getHeight()).isEqualTo(3508);
        // Rand bleibt weiss, Mitte traegt QR (dunkle Pixel).
        assertThat(img.getRGB(10, 10) & 0xFFFFFF).isEqualTo(0xFFFFFF);
        assertThat(dunkel(img)).isGreaterThan(10_000);

        byte[] gross = dienst.bogen("https://stampit.test/karte-neu/CARD-1", t, AufkleberService.Groesse.GROSS);
        BufferedImage g = ImageIO.read(new ByteArrayInputStream(gross));
        assertThat(g.getWidth()).isEqualTo(2480);
    }

    @Test
    void einzeln_600px() throws Exception {
        AufkleberService.Texte t = AufkleberService.texte(null, "Ein sehr langer Text der umgebrochen werden muss, damit er passt",
                "Laden", "");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(
                dienst.einzeln("https://stampit.test/karte-neu/CARD-1", t)));
        assertThat(img.getWidth()).isEqualTo(600);
        assertThat(img.getHeight()).isEqualTo(600);
        assertThat(dunkel(img)).isGreaterThan(1_000);
    }

    private static long dunkel(BufferedImage img) {
        long n = 0;
        for (int y = 0; y < img.getHeight(); y += 4) {
            for (int x = 0; x < img.getWidth(); x += 4) {
                if ((img.getRGB(x, y) & 0xFF) < 80) n++;
            }
        }
        return n;
    }
}
