package com.example.stemplekarte.wallet;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Das Push-Icon zeigt jetzt das Laden-Logo statt des StampIT-Logos.
 *
 * Die Falle dabei: icon.png ist quadratisch (29/58/87), Laden-Logos sind
 * fast immer breit. Wird das Logo einfach in das Quadrat skaliert, kommt
 * bei 480x150 ein 29x9-Streifen heraus - auf dem Sperrbildschirm nicht mehr
 * zu erkennen. Deshalb bleibt das Seitenverhaeltnis erhalten und der Rest
 * der Flaeche ist Kartenfarbe.
 *
 * Das Icon steckt in jedem Pass und erscheint auch in der Wallet-Liste,
 * ein Fehler hier faellt also auf jeder Karte auf - auch auf den
 * Stempelkarten, die schon in echten Laeden liegen.
 */
class PassIconTest {

    /** Vollflaechiges Logo in der angegebenen Groesse, deckend rot. */
    private BufferedImage logo(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private boolean istRot(BufferedImage img, int x, int y) {
        Color c = new Color(img.getRGB(x, y), true);
        return c.getRed() > 150 && c.getGreen() < 100 && c.getBlue() < 100;
    }

    /** Hoehe und Breite des roten Bereichs. */
    private int[] rotesRechteck(BufferedImage img) {
        int minX = img.getWidth(), maxX = -1, minY = img.getHeight(), maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (istRot(img, x, y)) {
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
        }
        return new int[]{maxX - minX + 1, maxY - minY + 1};
    }

    @Test
    void iconHatImmerDieAngeforderteGroesse() {
        for (int size : new int[]{29, 58, 87}) {
            BufferedImage icon = PassTemplateGenerator.iconMitLogo(logo(480, 150), Color.BLUE, size);
            assertThat(icon.getWidth()).isEqualTo(size);
            assertThat(icon.getHeight()).isEqualTo(size);
        }
    }

    @Test
    void breitesLogoWirdNichtAufsQuadratGezogen() {
        // 480x150 ist 3,2:1 - im Icon muss dasselbe Verhaeltnis stehen.
        BufferedImage icon = PassTemplateGenerator.iconMitLogo(logo(480, 150), Color.BLUE, 87);
        int[] rechteck = rotesRechteck(icon);

        double verhaeltnis = (double) rechteck[0] / rechteck[1];
        assertThat(verhaeltnis).isCloseTo(3.2, org.assertj.core.data.Offset.offset(0.3));
        // Und es bleibt Platz fuer die Kartenfarbe darueber und darunter.
        assertThat(rechteck[1]).isLessThan(87);
    }

    @Test
    void hohesLogoWirdEbenfallsNichtVerzerrt() {
        BufferedImage icon = PassTemplateGenerator.iconMitLogo(logo(150, 480), Color.BLUE, 87);
        int[] rechteck = rotesRechteck(icon);

        double verhaeltnis = (double) rechteck[1] / rechteck[0];
        assertThat(verhaeltnis).isCloseTo(3.2, org.assertj.core.data.Offset.offset(0.3));
        assertThat(rechteck[0]).isLessThan(87);
    }

    @Test
    void logoSitztMittigUndDieFlaecheHatDieKartenfarbe() {
        BufferedImage icon = PassTemplateGenerator.iconMitLogo(logo(480, 150), Color.BLUE, 87);

        assertThat(istRot(icon, 43, 43)).as("Mitte zeigt das Logo").isTrue();
        // Oben und unten mittig: Kartenfarbe, nicht durchsichtig.
        Color oben = new Color(icon.getRGB(43, 2), true);
        assertThat(oben.getAlpha()).isEqualTo(255);
        assertThat(oben.getBlue()).isGreaterThan(200);
        assertThat(istRot(icon, 43, 2)).isFalse();
        assertThat(istRot(icon, 43, 84)).isFalse();
    }

    @Test
    void quadratischesLogoBekommtRandUndKlebtNichtInDerEcke() {
        BufferedImage icon = PassTemplateGenerator.iconMitLogo(logo(200, 200), Color.BLUE, 87);
        int[] rechteck = rotesRechteck(icon);

        assertThat(rechteck[0]).isEqualTo(rechteck[1]);
        assertThat(rechteck[0]).isLessThan(87);
        // Ecke bleibt frei - dort sind die runden Kanten des Icons.
        assertThat(istRot(icon, 1, 1)).isFalse();
    }

    @Test
    void winzigesLogoStuerztNichtAb() {
        BufferedImage icon = PassTemplateGenerator.iconMitLogo(logo(1, 1), Color.BLUE, 29);
        assertThat(icon.getWidth()).isEqualTo(29);
    }
}
