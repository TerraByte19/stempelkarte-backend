package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CardType;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.service.RewardService;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Die neuen Streifen-Stile: Balken, Ring, Fuellstand, Foto.
 *
 * Der Streifen ist die einzige Bildflaeche der Karte und liegt unter den
 * Feldern des Passes. Zwei Dinge gehen hier leicht schief und faellt erst
 * auf dem Geraet auf: der Fortschritt stimmt nicht mit der Zahl im Feld
 * ueberein, oder die Grafik liegt dort, wo iOS Text hinzeichnet.
 *
 * Deshalb pruefen diese Tests Pixel und nicht nur "es kommt ein Bild raus".
 */
class StreifenStilTest {

    private static final Color AKZENT = new Color(250, 200, 117);

    /** Zaehlt Pixel, die deutlich nach Akzentfarbe aussehen. */
    private int akzentPixel(BufferedImage img, int x0, int x1, int y0, int y1) {
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                Color c = new Color(img.getRGB(x, y), true);
                if (c.getAlpha() > 200 && c.getRed() > 200 && c.getGreen() > 150 && c.getBlue() < 180) n++;
            }
        }
        return n;
    }

    private int akzentPixel(BufferedImage img) {
        return akzentPixel(img, 0, img.getWidth(), 0, img.getHeight());
    }

    // ── Anteil ────────────────────────────────────────────────────────────

    @Test
    void anteilRechnetStandDurchZiel() {
        assertThat(PassTemplateGenerator.anteil(5, 10)).isEqualTo(0.5);
        assertThat(PassTemplateGenerator.anteil(0, 10)).isEqualTo(0.0);
        assertThat(PassTemplateGenerator.anteil(10, 10)).isEqualTo(1.0);
    }

    /**
     * Ueber dem Ziel darf der Balken nicht ueber den Rand hinauslaufen.
     * Bei Punktekarten ist das der Normalfall: der Stand steht oft ueber
     * der naechsten Praemie, solange nicht eingeloest wurde.
     */
    @Test
    void anteilIstGedeckelt() {
        assertThat(PassTemplateGenerator.anteil(30, 10)).isEqualTo(1.0);
        assertThat(PassTemplateGenerator.anteil(-5, 10)).isEqualTo(0.0);
    }

    /** Eine Karte ohne Ziel (Schwelle 0) gilt als voll, nicht als Division durch null. */
    @Test
    void zielVonNullStuerztNichtAb() {
        assertThat(PassTemplateGenerator.anteil(3, 0)).isEqualTo(1.0);
    }

    // ── Stil-Erkennung ────────────────────────────────────────────────────

    @Test
    void nurStreifenStileZeichnenEinenStreifen() {
        for (String stil : new String[]{"grid", "balken", "ring", "fuellstand", "foto", "FOTO"}) {
            assertThat(PassTemplateGenerator.istStreifenStil(stil)).as(stil).isTrue();
        }
        assertThat(PassTemplateGenerator.istStreifenStil("number")).isFalse();
        assertThat(PassTemplateGenerator.istStreifenStil(null)).isFalse();
    }

    // ── Balken ────────────────────────────────────────────────────────────

    @Test
    void balkenWaechstMitDemStand() {
        int leer = akzentPixel(PassTemplateGenerator.renderBalken(0.0, AKZENT, 1125, 432));
        int halb = akzentPixel(PassTemplateGenerator.renderBalken(0.5, AKZENT, 1125, 432));
        int voll = akzentPixel(PassTemplateGenerator.renderBalken(1.0, AKZENT, 1125, 432));

        assertThat(leer).isZero();
        assertThat(halb).isGreaterThan(0);
        assertThat(voll).isGreaterThan(halb * 3 / 2);
    }

    @Test
    void balkenFuelltVonLinks() {
        BufferedImage img = PassTemplateGenerator.renderBalken(0.5, AKZENT, 1125, 432);
        int links = akzentPixel(img, 0, 560, 0, 432);
        int rechts = akzentPixel(img, 565, 1125, 0, 432);

        assertThat(links).isGreaterThan(0);
        assertThat(rechts).isZero();
    }

    /**
     * iOS zeichnet das primaere Feld in die obere Haelfte des Streifens.
     * Liegt der Balken dort, steht die Schrift auf der Grafik.
     */
    @Test
    void balkenBleibtAusDerOberenHaelfte() {
        BufferedImage img = PassTemplateGenerator.renderBalken(1.0, AKZENT, 1125, 432);
        assertThat(akzentPixel(img, 0, 1125, 0, 216)).isZero();
    }

    // ── Ring ──────────────────────────────────────────────────────────────

    @Test
    void ringLaesstLinksPlatzFuerText() {
        BufferedImage img = PassTemplateGenerator.renderRing(0.75, AKZENT, 1125, 432);

        assertThat(akzentPixel(img, 0, 700, 0, 432)).isZero();
        assertThat(akzentPixel(img, 700, 1125, 0, 432)).isGreaterThan(0);
    }

    @Test
    void ringWaechstMitDemStand() {
        int viertel = akzentPixel(PassTemplateGenerator.renderRing(0.25, AKZENT, 1125, 432));
        int voll = akzentPixel(PassTemplateGenerator.renderRing(1.0, AKZENT, 1125, 432));

        assertThat(viertel).isGreaterThan(0);
        assertThat(voll).isGreaterThan(viertel * 2);
    }

    @Test
    void leererRingZeigtNurDieBahn() {
        assertThat(akzentPixel(PassTemplateGenerator.renderRing(0.0, AKZENT, 1125, 432))).isZero();
    }

    // ── Fuellstand ────────────────────────────────────────────────────────

    @Test
    void fuellstandSteigtMitDemStand() {
        int leer = akzentPixel(PassTemplateGenerator.renderFuellstand(0.0, AKZENT, 1125, 432));
        int halb = akzentPixel(PassTemplateGenerator.renderFuellstand(0.5, AKZENT, 1125, 432));
        int voll = akzentPixel(PassTemplateGenerator.renderFuellstand(1.0, AKZENT, 1125, 432));

        // Auch leer bleibt ein Sockel stehen, sonst wirkt die Karte kaputt.
        assertThat(leer).isGreaterThan(0);
        assertThat(halb).isGreaterThan(leer * 2);
        assertThat(voll).isGreaterThan(halb);
    }

    // ── Foto ──────────────────────────────────────────────────────────────

    /** Ein Bild in einem anderen Seitenverhaeltnis, damit der Zuschnitt zaehlt. */
    private BufferedImage foto(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    @Test
    void fotoFuelltDenStreifenGanzAus() {
        BufferedImage img = PassTemplateGenerator.renderFoto(foto(800, 800), 1125, 432);

        assertThat(img.getWidth()).isEqualTo(1125);
        for (int[] punkt : new int[][]{{0, 0}, {1124, 0}, {0, 431}, {1124, 431}, {562, 216}}) {
            assertThat(new Color(img.getRGB(punkt[0], punkt[1]), true).getAlpha())
                    .as("Punkt %d/%d", punkt[0], punkt[1]).isEqualTo(255);
        }
    }

    /**
     * Ueber dem Foto steht weisser Text. Ohne die dunkle Seite verschwindet
     * er auf hellen Motiven - deshalb wird links abgedunkelt.
     */
    @Test
    void fotoIstLinksDunklerAlsRechts() {
        BufferedImage img = PassTemplateGenerator.renderFoto(foto(2000, 800), 1125, 432);
        int links = new Color(img.getRGB(40, 216)).getRed();
        int rechts = new Color(img.getRGB(1080, 216)).getRed();

        assertThat(links).isLessThan(rechts - 80);
        assertThat(rechts).isGreaterThan(200);
    }

    // ── Fortschritt aus der Karte ─────────────────────────────────────────

    @Test
    void stempelkarteRechnetMitDerSchwelle() {
        PassTemplateGenerator gen = new PassTemplateGenerator(mock(RewardService.class));
        Card card = mock(Card.class);
        when(card.getType()).thenReturn(CardType.STAMP);
        when(card.getRewardThreshold()).thenReturn(8);
        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getStamps()).thenReturn(2);

        assertThat(gen.fortschritt(card, cc)).isEqualTo(0.25);
    }

    /**
     * Punktekarte: das Ziel ist die naechste Praemie aus dem Katalog,
     * dieselbe, die als Text auf der Karte steht. Nimmt der Streifen ein
     * anderes Ziel, zeigen Balken und Text verschiedene Staende.
     */
    @Test
    void punktekarteRechnetMitDerNaechstenPraemie() {
        RewardService rewards = mock(RewardService.class);
        Card card = mock(Card.class);
        when(card.getType()).thenReturn(CardType.POINTS);
        Reward kaffee = Reward.create(card, "Kaffee", 10_000, 0);
        Reward kuchen = Reward.create(card, "Kuchen", 25_000, 1);
        when(rewards.list(card)).thenReturn(List.of(kaffee, kuchen));

        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getPointsX100()).thenReturn(5_000L);

        PassTemplateGenerator gen = new PassTemplateGenerator(rewards);
        assertThat(gen.fortschritt(card, cc)).isEqualTo(0.5);
    }

    @Test
    void punktekarteOhnePraemienZeigtLeer() {
        RewardService rewards = mock(RewardService.class);
        Card card = mock(Card.class);
        when(card.getType()).thenReturn(CardType.POINTS);
        when(rewards.list(card)).thenReturn(List.of());
        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getPointsX100()).thenReturn(4_000L);

        PassTemplateGenerator gen = new PassTemplateGenerator(rewards);
        assertThat(gen.fortschritt(card, cc)).isEqualTo(0.0);
    }
}
