package com.example.stemplekarte.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aufkleber fuer Liefertueten und Verpackungen: QR-Code zur Anmeldeseite
 * der Karte plus kurzer Text, als A4-Bogen (300 dpi) zum Drucken auf
 * Etikettenpapier oder als einzelnes Bild. Reines AWT wie die Pass-Bilder.
 */
@Service
public class AufkleberService {

    public static final int TITEL_MAX = 30;
    public static final int ZEILE_MAX = 70;
    public static final String TITEL_STANDARD = "Stempel sammeln!";
    public static final String ZEILE_STANDARD = "QR-Code scannen und die Karte aufs Handy holen";

    static final int DPI = 300;
    static final int A4_BREITE = 2480;
    static final int A4_HOEHE = 3508;
    static final int ABSTAND = 35;   // ~3 mm zwischen den Aufklebern
    static final int EINZELN_PX = 600;

    /** Groesse eines Aufklebers und Raster auf dem A4-Bogen. */
    public enum Groesse {
        KLEIN(45, 4, 6),
        GROSS(70, 2, 3);

        final int mm;
        final int spalten;
        final int zeilen;

        Groesse(int mm, int spalten, int zeilen) {
            this.mm = mm;
            this.spalten = spalten;
            this.zeilen = zeilen;
        }

        int px() { return (int) Math.round(mm * DPI / 25.4); }
        public int anzahl() { return spalten * zeilen; }

        public static Groesse von(String key) {
            if (key == null || key.isBlank()) return KLEIN;
            try {
                return valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unbekannte Größe: " + key);
            }
        }
    }

    public record Texte(String titel, String zeile, String laden, String karte) {}

    /** Texte bereinigen und begrenzen; leer = Standard. */
    public static Texte texte(String titel, String zeile, String laden, String karte) {
        String t = sauber(titel);
        String z = sauber(zeile);
        if (t.length() > TITEL_MAX) throw new IllegalArgumentException("Überschrift höchstens " + TITEL_MAX + " Zeichen");
        if (z.length() > ZEILE_MAX) throw new IllegalArgumentException("Text höchstens " + ZEILE_MAX + " Zeichen");
        return new Texte(t.isEmpty() ? TITEL_STANDARD : t, z.isEmpty() ? ZEILE_STANDARD : z,
                sauber(laden), sauber(karte));
    }

    private static String sauber(String s) {
        return s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").trim();
    }

    /** A4-Bogen voller Aufkleber. */
    public byte[] bogen(String link, Texte texte, Groesse g) throws Exception {
        BufferedImage blatt = new BufferedImage(A4_BREITE, A4_HOEHE, BufferedImage.TYPE_INT_RGB);
        Graphics2D gfx = blatt.createGraphics();
        gfx.setColor(Color.WHITE);
        gfx.fillRect(0, 0, A4_BREITE, A4_HOEHE);
        int px = g.px();
        BufferedImage einer = aufkleber(link, texte, px);
        int breite = g.spalten * px + (g.spalten - 1) * ABSTAND;
        int hoehe = g.zeilen * px + (g.zeilen - 1) * ABSTAND;
        int x0 = (A4_BREITE - breite) / 2;
        int y0 = (A4_HOEHE - hoehe) / 2;
        for (int z = 0; z < g.zeilen; z++) {
            for (int s = 0; s < g.spalten; s++) {
                gfx.drawImage(einer, x0 + s * (px + ABSTAND), y0 + z * (px + ABSTAND), null);
            }
        }
        gfx.dispose();
        return png(blatt);
    }

    /** Ein einzelner Aufkleber (600 px), z. B. fuer die Vorschau oder digitale Nutzung. */
    public byte[] einzeln(String link, Texte texte) throws Exception {
        return png(aufkleber(link, texte, EINZELN_PX));
    }

    /** Quadrat mit Rand, Ueberschrift oben, QR in der Mitte, Text und Laden unten. */
    BufferedImage aufkleber(String link, Texte t, int px) throws Exception {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, px, px);

        // Schnittkante: duenner grauer Rahmen mit runden Ecken.
        int rand = Math.max(2, px / 150);
        g.setColor(new Color(0xBBBBBB));
        g.setStroke(new BasicStroke(rand));
        int eck = px / 10;
        g.drawRoundRect(rand / 2, rand / 2, px - rand, px - rand, eck, eck);

        int innen = px / 14;
        int y = innen;

        // Ueberschrift.
        g.setColor(new Color(0x111111));
        int titelHoehe = zeichneZentriert(g, t.titel(), px, y, px - 2 * innen, (int) (px * 0.095), Font.BOLD, 1);
        y += titelHoehe + px / 40;

        // QR-Code.
        int qrGroesse = (int) (px * 0.52);
        var matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, qrGroesse, qrGroesse,
                Map.of(EncodeHintType.MARGIN, 1));
        BufferedImage qr = MatrixToImageWriter.toBufferedImage(matrix);
        g.drawImage(qr, (px - qrGroesse) / 2, y, qrGroesse, qrGroesse, null);
        y += qrGroesse + px / 40;

        // Erklaerung, bis zu zwei Zeilen.
        g.setColor(new Color(0x333333));
        int textHoehe = zeichneZentriert(g, t.zeile(), px, y, px - 2 * innen, (int) (px * 0.055), Font.PLAIN, 2);
        y += textHoehe;

        // Laden und Karte unten, klein.
        String fuss = t.laden().isEmpty() ? t.karte()
                : t.karte().isEmpty() ? t.laden() : t.laden() + " · " + t.karte();
        if (!fuss.isEmpty()) {
            g.setColor(new Color(0x666666));
            int fussGroesse = (int) (px * 0.045);
            int fussY = px - innen - fussGroesse;
            if (fussY > y) zeichneZentriert(g, fuss, px, fussY, px - 2 * innen, fussGroesse, Font.PLAIN, 1);
        }
        g.dispose();
        return img;
    }

    /**
     * Text mittig ab y zeichnen; Schrift verkleinern, bis er in maxZeilen
     * Zeilen der Breite passt. Gibt die belegte Hoehe zurueck.
     */
    private static int zeichneZentriert(Graphics2D g, String text, int breite, int y, int maxBreite,
                                        int schrift, int stil, int maxZeilen) {
        if (text == null || text.isEmpty()) return 0;
        for (int groesse = schrift; groesse >= 8; groesse -= Math.max(1, schrift / 12)) {
            Font f = new Font(Font.SANS_SERIF, stil, groesse);
            FontMetrics fm = g.getFontMetrics(f);
            List<String> zeilen = umbrechen(text, fm, maxBreite);
            if (zeilen.size() > maxZeilen) continue;
            boolean passt = zeilen.stream().allMatch(z -> fm.stringWidth(z) <= maxBreite);
            if (!passt && groesse > 8) continue;
            g.setFont(f);
            int zeilenHoehe = fm.getHeight();
            int yy = y + fm.getAscent();
            for (String z : zeilen) {
                g.drawString(z, (breite - fm.stringWidth(z)) / 2, yy);
                yy += zeilenHoehe;
            }
            return zeilen.size() * zeilenHoehe;
        }
        return 0;
    }

    private static List<String> umbrechen(String text, FontMetrics fm, int maxBreite) {
        List<String> zeilen = new ArrayList<>();
        StringBuilder aktuell = new StringBuilder();
        for (String wort : text.split(" ")) {
            String probe = aktuell.isEmpty() ? wort : aktuell + " " + wort;
            if (fm.stringWidth(probe) <= maxBreite || aktuell.isEmpty()) {
                aktuell.setLength(0);
                aktuell.append(probe);
            } else {
                zeilen.add(aktuell.toString());
                aktuell.setLength(0);
                aktuell.append(wort);
            }
        }
        if (!aktuell.isEmpty()) zeilen.add(aktuell.toString());
        return zeilen;
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
