package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class PassTemplateGenerator {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PassTemplateGenerator.class);

    /**
     * Laedt ein Bild von einer externen URL (Cloudinary) und protokolliert
     * Start, Ende und Dauer.
     *
     * Wichtig fuer die Fehlersuche: {@code ImageIO.read(URL)} nutzt die
     * Default-URLConnection - ohne Connect- und Read-Timeout. Bleibt die
     * Gegenstelle stumm, haengt dieser Aufruf unbegrenzt und blockiert den
     * Request, in dem der Pass gebaut wird. Im Log sieht man das daran, dass
     * eine BILD-START-Zeile OHNE passende BILD-FERTIG-Zeile bleibt.
     */
    private static BufferedImage ladeBild(String zweck, String url) throws IOException {
        log.info("[WALLET] BILD-START zweck={} url={}", zweck, url);
        // Zweite Sperre. Geprueft wird schon beim Speichern - aber hier
        // stehen auch Adressen aus der Zeit davor, und ein Name kann
        // zwischen Speichern und Abruf auf eine interne Adresse zeigen.
        // Faellt eine Adresse durch, greift die uebliche Ersatzkette
        // (Text-Logo, Balken, Plattform-Icon).
        if (!com.example.stemplekarte.service.BildUrl.istErlaubt(url)) {
            log.warn("[WALLET] BILD-ABGELEHNT zweck={} url={} grund=nicht_erlaubte_adresse", zweck, url);
            throw new IOException("Bild-Adresse nicht erlaubt: " + url);
        }
        long t0 = System.nanoTime();
        try {
            BufferedImage img = ImageIO.read(new URL(url));
            long dauerMs = (System.nanoTime() - t0) / 1_000_000L;
            if (img == null) {
                log.warn("[WALLET] BILD-LEER zweck={} url={} dauerMs={} (kein lesbares Bildformat)",
                        zweck, url, dauerMs);
            } else {
                log.info("[WALLET] BILD-FERTIG zweck={} dauerMs={} groesse={}x{}",
                        zweck, dauerMs, img.getWidth(), img.getHeight());
            }
            return img;
        } catch (Exception e) {
            log.warn("[WALLET] BILD-FEHLER zweck={} url={} dauerMs={} fehler={}",
                    zweck, url, (System.nanoTime() - t0) / 1_000_000L, e.toString());
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    /** Streifen-Stile: jeder zeichnet strip.png, "number" zeichnet keinen. */
    private static final java.util.Set<String> STREIFEN_STILE =
            java.util.Set.of("grid", "balken", "ring", "fuellstand", "foto");

    private final com.example.stemplekarte.service.RewardService rewardService;

    public PassTemplateGenerator(com.example.stemplekarte.service.RewardService rewardService) {
        this.rewardService = rewardService;
    }

    static boolean istStreifenStil(String walletStyle) {
        return walletStyle != null && STREIFEN_STILE.contains(walletStyle.toLowerCase());
    }

    // Apple erwartet icon.png / @2x / @3x - dieselbe Reihenfolge in beiden Feldern.
    // 38 Punkte laut aktuellen Design-Richtlinien (frueher 29), also 38/76/114 Pixel.
    private static final int[] ICON_GROESSEN = {38, 76, 114};
    private static final String[] ICON_DATEIEN = {"icon.png", "icon@2x.png", "icon@3x.png"};

    @Value("${stempelkarte.upload-path:./uploads}")
    private String uploadPath;

    @Value("${stempelkarte.apple.pass-type-identifier:pass.com.example.stempelkarte}")
    private String passTypeIdentifier;

    @Value("${stempelkarte.apple.team-identifier:ABCDE12345}")
    private String teamIdentifier;

    public String generateTemplate(CustomerCard cc) throws IOException {
        Card card = cc.getCard();
        Shop shop = card.getShop();
        int threshold = card.getRewardThreshold();
        // Gedeckelt: nach einem nachtraeglich gesenkten Schwellwert kann der
        // Stand ueber der Schwelle liegen - das Raster hat aber nur threshold
        // Felder.
        int stamps = Math.min(cc.getStamps(), threshold);

        // Karten-Design hat Vorrang, Fallback auf Shop
        String bgColor = notBlank(card.getColorBackground()) ? card.getColorBackground() : shop.getColorBackground();
        String fgColor = notBlank(card.getColorForeground()) ? card.getColorForeground() : shop.getColorForeground();
        String labelColor = notBlank(card.getColorLabel()) ? card.getColorLabel() : shop.getColorLabel();
        String logoUrl = notBlank(card.getLogoUrl()) ? card.getLogoUrl() : shop.getLogoUrl();
        String walletStyle = card.getWalletStyle();
        String stampColor = card.getStampColor();
        String stampIconType = card.getStampIconType();
        String stampPreset = card.getStampPreset();
        String stampIconUrl = card.getStampIconUrl();
        String emptyStampStyle = card.getEmptyStampStyle();

        String passDir = uploadPath + "/pass-templates/" + cc.getId() + ".pass";
        Path templatePath = Paths.get(passDir);
        Files.createDirectories(templatePath);

        Files.writeString(templatePath.resolve("pass.json"),
                generatePassJson(shop.getName(), card.getName(), bgColor, fgColor, labelColor));

        generateLogoImages(logoUrl, shop.getName(), bgColor, templatePath);
        generateIconImages(logoUrl, bgColor, templatePath);

        deleteStripImages(templatePath);
        if (istStreifenStil(walletStyle)) {
            if ("grid".equalsIgnoreCase(walletStyle)) {
                generateStripImages(stamps, threshold, walletStyle, stampColor,
                        stampIconType, stampPreset, stampIconUrl, emptyStampStyle, templatePath);
            } else {
                generateFortschrittStreifen(walletStyle, fortschritt(card, cc),
                        stampColor, card.getStripImageUrl(), templatePath);
            }
        }

        return passDir;
    }

    // ── pass.json ─────────────────────────────────────────────────────────────

    private String generatePassJson(String orgName, String cardName,
                                    String bgColor, String fgColor, String labelColor) {
        return """
                {
                  "formatVersion": 1,
                  "passTypeIdentifier": "%s",
                  "teamIdentifier": "%s",
                  "organizationName": "%s",
                  "description": "%s",
                  "logoText": "%s",
                  "foregroundColor": "%s",
                  "backgroundColor": "%s",
                  "labelColor": "%s",
                  "storeCard": {}
                }
                """.formatted(
                passTypeIdentifier,
                teamIdentifier,
                orgName,
                cardName,
                orgName,
                hexToRgb(fgColor != null ? fgColor : "#FFFFFF"),
                hexToRgb(bgColor != null ? bgColor : "#3C3489"),
                hexToRgb(labelColor != null ? labelColor : "#FAC875")
        );
    }

    // ── Stempel-Raster (Strip) ────────────────────────────────────────────────

    private void generateStripImages(int stamps, int threshold,
                                     String walletStyle, String stampColor,
                                     String stampIconType, String stampPreset,
                                     String stampIconUrl, String emptyStampStyle,
                                     Path templatePath) throws IOException {
        BufferedImage customIcon = loadCustomIcon(stampIconType, stampIconUrl);
        // 375x144 Punkte laut Apple, also 375/750/1125 Pixel breit. Vorher stand
        // hier 320x110 - die Masse alter Geraete. Auf heutigen iPhones wurde das
        // Bild hochskaliert und wirkte weich.
        ImageIO.write(renderStrip(stamps, threshold, stampColor, stampIconType,
                        stampPreset, stampIconUrl, emptyStampStyle, 375, 144, customIcon),
                "PNG", templatePath.resolve("strip.png").toFile());
        ImageIO.write(renderStrip(stamps, threshold, stampColor, stampIconType,
                        stampPreset, stampIconUrl, emptyStampStyle, 750, 288, customIcon),
                "PNG", templatePath.resolve("strip@2x.png").toFile());
        ImageIO.write(renderStrip(stamps, threshold, stampColor, stampIconType,
                        stampPreset, stampIconUrl, emptyStampStyle, 1125, 432, customIcon),
                "PNG", templatePath.resolve("strip@3x.png").toFile());
    }

    private BufferedImage loadCustomIcon(String stampIconType, String stampIconUrl) {
        if ("upload".equalsIgnoreCase(stampIconType)
                && notBlank(stampIconUrl)) {
            try {
                return ladeBild("stempel-icon", stampIconUrl);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private BufferedImage renderStrip(int stamps, int threshold,
                                      String stampColorHex, String stampIconType,
                                      String stampPreset, String stampIconUrl,
                                      String emptyStampStyle,
                                      int w, int h, BufferedImage customIcon) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        if (threshold < 1) threshold = 1;
        int cols = threshold <= 5 ? threshold : (int) Math.ceil(threshold / 2.0);
        int rows = (int) Math.ceil((double) threshold / cols);

        double padX = w * 0.05, padY = h * 0.10;
        double contentW = w - 2 * padX, contentH = h - 2 * padY;
        double cellW = contentW / cols, cellH = contentH / rows;
        double diameter = Math.min(cellW, cellH) * 0.80;

        Color stampColor = hexToColor(stampColorHex != null ? stampColorHex : "#6F4E37");
        String emptyStyle = emptyStampStyle == null ? "number" : emptyStampStyle;
        boolean useCustom = "upload".equalsIgnoreCase(stampIconType) && customIcon != null;
        String preset = stampPreset == null ? "coffee" : stampPreset;

        for (int i = 0; i < threshold; i++) {
            int r = i / cols, c = i % cols;
            int itemsInRow = Math.min(cols, threshold - r * cols);
            double rowOffset = (cols - itemsInRow) * cellW / 2.0;
            double cx = padX + rowOffset + c * cellW + cellW / 2;
            double cy = padY + r * cellH + cellH / 2;
            boolean filled = i < stamps;
            drawStamp(g, filled, i + 1, cx, cy, diameter,
                    useCustom, customIcon, preset, stampColor, emptyStyle);
        }

        g.dispose();
        return img;
    }

    private void drawStamp(Graphics2D g, boolean filled, int number,
                           double cx, double cy, double d,
                           boolean useCustom, BufferedImage customIcon,
                           String preset, Color stampColor, String emptyStyle) {
        double r = d / 2;
        if (filled) {
            g.setColor(new Color(255, 255, 255, 242));
            g.fill(new Ellipse2D.Double(cx - r, cy - r, d, d));
            if (useCustom) {
                drawImageInCircle(g, customIcon, cx, cy, d * 0.92, 1f);
            } else {
                drawPreset(g, preset, cx, cy, d * 0.55, stampColor, 255);
            }
        } else {
            if ("number".equalsIgnoreCase(emptyStyle)) {
                g.setColor(new Color(255, 255, 255, 85));
                g.setStroke(new BasicStroke((float) Math.max(2, d * 0.045)));
                g.draw(new Ellipse2D.Double(cx - r, cy - r, d, d));
                g.setColor(new Color(255, 255, 255, 140));
                g.setFont(new Font("Arial", Font.BOLD, (int) (d * 0.42)));
                FontMetrics fm = g.getFontMetrics();
                String s = String.valueOf(number);
                g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0),
                        (float) (cy + fm.getAscent() / 2.0 - fm.getDescent() / 2.0));
            } else {
                g.setColor(new Color(255, 255, 255, 45));
                g.fill(new Ellipse2D.Double(cx - r, cy - r, d, d));
                if (useCustom) {
                    drawImageInCircle(g, customIcon, cx, cy, d * 0.92, 0.35f);
                } else {
                    drawPreset(g, preset, cx, cy, d * 0.55, new Color(255, 255, 255), 90);
                }
            }
        }
    }

    private void drawImageInCircle(Graphics2D g, BufferedImage img,
                                   double cx, double cy, double d, float alpha) {
        Shape oldClip = g.getClip();
        Composite oldComp = g.getComposite();
        g.setClip(new Ellipse2D.Double(cx - d / 2, cy - d / 2, d, d));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g.drawImage(img, (int) (cx - d / 2), (int) (cy - d / 2), (int) d, (int) d, null);
        g.setComposite(oldComp);
        g.setClip(oldClip);
    }

    private void drawPreset(Graphics2D g, String preset, double cx, double cy,
                            double size, Color base, int alpha) {
        Color col = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
        g.setColor(col);
        String p = preset == null ? "coffee" : preset.toLowerCase();
        switch (p) {
            case "star"   -> g.fill(starShape(cx, cy, size / 2));
            case "heart"  -> g.fill(heartShape(cx, cy, size / 2));
            case "square" -> {
                double s = size * 0.86;
                g.fill(new RoundRectangle2D.Double(cx - s / 2, cy - s / 2, s, s, s * 0.22, s * 0.22));
            }
            case "dot"    -> g.fill(new Ellipse2D.Double(cx - size / 2, cy - size / 2, size, size));
            default       -> drawCoffee(g, cx, cy, size, col);
        }
    }

    private void drawCoffee(Graphics2D g, double cx, double cy, double size, Color col) {
        double bw = size * 0.74, bh = size * 0.84;
        double bx = cx - size * 0.46, by = cy - bh / 2;
        g.setColor(col);
        g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, bw * 0.18, bw * 0.18));
        g.setStroke(new BasicStroke((float) (size * 0.12), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(bx + bw * 0.78, by + bh * 0.16, size * 0.34, bh * 0.52, 90, -180, Arc2D.OPEN));
    }

    private Shape starShape(double cx, double cy, double rOuter) {
        double rInner = rOuter * 0.42;
        Path2D p = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double ang = Math.PI / 2 + i * Math.PI / 5;
            double rad = (i % 2 == 0) ? rOuter : rInner;
            double x = cx + Math.cos(ang) * rad;
            double y = cy - Math.sin(ang) * rad;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    private Shape heartShape(double cx, double cy, double s) {
        Path2D p = new Path2D.Double();
        p.moveTo(cx, cy + s * 0.55);
        p.curveTo(cx - s * 1.2, cy - s * 0.2, cx - s * 0.4, cy - s * 1.0, cx, cy - s * 0.35);
        p.curveTo(cx + s * 0.4, cy - s * 1.0, cx + s * 1.2, cy - s * 0.2, cx, cy + s * 0.55);
        p.closePath();
        return p;
    }

    private void deleteStripImages(Path templatePath) {
        for (String n : new String[]{"strip.png", "strip@2x.png", "strip@3x.png"}) {
            try { Files.deleteIfExists(templatePath.resolve(n)); }
            catch (IOException ignored) {}
        }
    }

    // ── Logo / Icon ───────────────────────────────────────────────────────────

    private void generateLogoImages(String logoUrl, String shopName,
                                    String bgColor, Path templatePath) throws IOException {
        if (notBlank(logoUrl)) {
            try {
                BufferedImage logo = ladeBild("laden-logo", logoUrl);
                ImageIO.write(resizeImage(logo, 160, 50), "PNG",
                        templatePath.resolve("logo.png").toFile());
                ImageIO.write(resizeImage(logo, 320, 100), "PNG",
                        templatePath.resolve("logo@2x.png").toFile());
                // @3x fehlte bisher ganz; ohne die Datei skaliert iOS die @2x hoch.
                ImageIO.write(resizeImage(logo, 480, 150), "PNG",
                        templatePath.resolve("logo@3x.png").toFile());
                return;
            } catch (Exception e) {
                log.warn("[WALLET] BILD-FALLBACK zweck=laden-logo -> Text-Logo statt Bild", e);
            }
        }
        createTextLogo(shopName, bgColor, 160, 50,
                templatePath.resolve("logo.png").toString());
        createTextLogo(shopName, bgColor, 320, 100,
                templatePath.resolve("logo@2x.png").toString());
        createTextLogo(shopName, bgColor, 480, 150,
                templatePath.resolve("logo@3x.png").toString());
    }

    // --- ICON FÜR DIE PUSH-BENACHRICHTIGUNG ---
    // Reihenfolge: Laden-Logo auf der Kartenfarbe, sonst platform-icon.png aus
    // dem Classpath (src/main/resources/static/, überlebt jeden Render-Deploy),
    // sonst ein einfarbiges Viereck. Das Icon erscheint auch in der Wallet-Liste
    // und auf dem Sperrbildschirm, nicht nur in der Benachrichtigung.
    private void generateIconImages(String logoUrl, String bgColor, Path templatePath) throws IOException {
        if (notBlank(logoUrl)) {
            try {
                BufferedImage logo = ladeBild("laden-icon", logoUrl);
                if (logo != null) {
                    Color bg = hexToColor(bgColor);
                    for (int i = 0; i < ICON_GROESSEN.length; i++) {
                        ImageIO.write(iconMitLogo(logo, bg, ICON_GROESSEN[i]), "PNG",
                                templatePath.resolve(ICON_DATEIEN[i]).toFile());
                    }
                    return;
                }
            } catch (Exception e) {
                log.warn("[WALLET] BILD-FALLBACK zweck=laden-icon -> Plattform-Icon statt Laden-Logo", e);
            }
        }

        try (InputStream in = getClass().getResourceAsStream("/static/platform-icon.png")) {
            if (in != null) {
                BufferedImage baseIcon = ImageIO.read(in);
                if (baseIcon != null) {
                    for (int i = 0; i < ICON_GROESSEN.length; i++) {
                        int s = ICON_GROESSEN[i];
                        ImageIO.write(resizeImage(baseIcon, s, s), "PNG",
                                templatePath.resolve(ICON_DATEIEN[i]).toFile());
                    }
                    return;
                }
            }
        } catch (Exception e) {
            // Falls Lesen/Skalieren fehlschlägt, geht es unten beim Fallback weiter
        }

        // Fallback: einfarbiges Viereck, falls platform-icon.png nicht im Classpath liegt
        for (int i = 0; i < ICON_GROESSEN.length; i++) {
            createColorIcon(bgColor, ICON_GROESSEN[i],
                    templatePath.resolve(ICON_DATEIEN[i]).toString());
        }
    }

    /**
     * Zeichnet das Laden-Logo mittig auf ein Quadrat in der Kartenfarbe.
     *
     * <p>Das Icon ist quadratisch, die meisten Laden-Logos sind breit. Blosses
     * Skalieren würde aus einem 480x150-Logo einen 29x9-Streifen machen - hier
     * bleibt das Seitenverhältnis erhalten und der Rest ist Kartenfarbe.
     */
    static BufferedImage iconMitLogo(BufferedImage logo, Color bg, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(bg);
        // Volle Flaeche: iOS rundet die Ecken selbst. Runden wir zusaetzlich,
        // liegt eine zweite Rundung auf der ersten.
        g.fillRect(0, 0, size, size);

        // Rand, damit das Logo nicht an den gerundeten Ecken des Systems klebt
        int innen = Math.max(1, (int) Math.round(size * 0.78));
        double faktor = Math.min((double) innen / logo.getWidth(), (double) innen / logo.getHeight());
        int w = Math.max(1, (int) Math.round(logo.getWidth() * faktor));
        int h = Math.max(1, (int) Math.round(logo.getHeight() * faktor));
        g.drawImage(halbiereBisPasst(logo, w, h), (size - w) / 2, (size - h) / 2, w, h, null);
        g.dispose();
        return img;
    }

    /**
     * Verkleinert schrittweise um jeweils die Haelfte, bis das Ziel nah ist.
     *
     * Ein 480 breites Logo in einem Rutsch auf 23 Pixel zu ziehen ueberspringt
     * fast jede Pixelreihe - Schrift franst aus. Mehrere halbe Schritte mitteln
     * dagegen, das kostet hier nichts und ist bei 29 Pixeln der Unterschied
     * zwischen lesbar und Brei.
     */
    private static BufferedImage halbiereBisPasst(BufferedImage bild, int zielW, int zielH) {
        BufferedImage aktuell = bild;
        while (aktuell.getWidth() > zielW * 2 && aktuell.getHeight() > zielH * 2) {
            int w = Math.max(zielW, aktuell.getWidth() / 2);
            int h = Math.max(zielH, aktuell.getHeight() / 2);
            BufferedImage kleiner = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = kleiner.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(aktuell, 0, 0, w, h, null);
            g.dispose();
            aktuell = kleiner;
        }
        return aktuell;
    }

    // -- Fortschritts-Streifen ------------------------------------------------

    /**
     * Wie voll die Karte ist, als Anteil zwischen 0 und 1.
     *
     * <p>Stempelkarte: Stempel durch Schwelle. Punktekarte: Punktestand durch
     * die naechste Praemie aus dem Katalog - dieselbe Zeile, die auch auf der
     * Karte steht, damit Balken und Text nicht auseinanderlaufen.
     */
    double fortschritt(Card card, CustomerCard cc) {
        if (card.getType() == com.example.stemplekarte.model.CardType.POINTS) {
            var ziel = com.example.stemplekarte.service.RewardService
                    .naechstesZiel(rewardService.list(card), cc.getPointsX100());
            if (ziel == null) return 0.0;
            return anteil(cc.getPointsX100(), ziel.getCostPointsX100());
        }
        return anteil(cc.getStamps(), card.getRewardThreshold());
    }

    /** Gedeckelt auf 0..1; ein Ziel von 0 oder weniger gilt als voll. */
    static double anteil(long stand, long ziel) {
        if (ziel <= 0) return 1.0;
        if (stand <= 0) return 0.0;
        return Math.min(1.0, (double) stand / ziel);
    }

    private void generateFortschrittStreifen(String stil, double anteil, String akzentFarbe,
                                             String stripImageUrl, Path templatePath) throws IOException {
        BufferedImage foto = null;
        if ("foto".equalsIgnoreCase(stil)) {
            if (notBlank(stripImageUrl)) {
                try {
                    foto = ladeBild("streifen-foto", stripImageUrl);
                } catch (Exception e) {
                    log.warn("[WALLET] BILD-FALLBACK zweck=streifen-foto -> Balken statt Foto", e);
                }
            }
            // Ohne Bild bleibt der Streifen nicht leer: der Balken zeigt
            // denselben Stand und die Felder sitzen weiter richtig.
            if (foto == null) stil = "balken";
        }

        Color akzent = hexToColor(notBlank(akzentFarbe) ? akzentFarbe : "#FAC875");
        int[][] masse = {{375, 144}, {750, 288}, {1125, 432}};
        String[] namen = {"strip.png", "strip@2x.png", "strip@3x.png"};
        for (int i = 0; i < masse.length; i++) {
            int w = masse[i][0], h = masse[i][1];
            BufferedImage img = switch (stil.toLowerCase()) {
                case "ring" -> renderRing(anteil, akzent, w, h);
                case "fuellstand" -> renderFuellstand(anteil, akzent, w, h);
                case "foto" -> renderFoto(foto, w, h);
                default -> renderBalken(anteil, akzent, w, h);
            };
            ImageIO.write(img, "PNG", templatePath.resolve(namen[i]).toFile());
        }
    }

    private static Graphics2D leinwand(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        return g;
    }

    /** Waagerechter Balken, links gefuellt. Hintergrund bleibt durchsichtig. */
    static BufferedImage renderBalken(double anteil, Color akzent, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = leinwand(img);
        int randX = Math.round(w * 0.06f);
        int hoehe = Math.max(4, Math.round(h * 0.16f));
        // Unteres Drittel: darueber zeigt iOS das primaere Feld an.
        int y = Math.round(h * 0.68f);
        int breite = w - 2 * randX;
        int bogen = hoehe;

        g.setColor(new Color(255, 255, 255, 64));
        g.fillRoundRect(randX, y, breite, hoehe, bogen, bogen);
        int gefuellt = (int) Math.round(breite * Math.max(0, Math.min(1, anteil)));
        if (gefuellt > 0) {
            g.setColor(akzent);
            g.fillRoundRect(randX, y, Math.max(hoehe, gefuellt), hoehe, bogen, bogen);
        }
        g.dispose();
        return img;
    }

    /** Ring rechts, Text hat links Platz. */
    static BufferedImage renderRing(double anteil, Color akzent, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = leinwand(img);
        int durchmesser = Math.round(h * 0.62f);
        int x = w - durchmesser - Math.round(w * 0.07f);
        int y = (h - durchmesser) / 2;
        int dicke = Math.max(3, Math.round(durchmesser * 0.16f));

        g.setStroke(new java.awt.BasicStroke(dicke, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 64));
        g.drawOval(x, y, durchmesser, durchmesser);

        int winkel = (int) Math.round(360 * Math.max(0, Math.min(1, anteil)));
        if (winkel > 0) {
            g.setColor(akzent);
            // Start oben, im Uhrzeigersinn - so liest man einen Fortschritt.
            g.drawArc(x, y, durchmesser, durchmesser, 90, -winkel);
        }
        g.dispose();
        return img;
    }

    /** Welle, die mit dem Stand steigt. */
    static BufferedImage renderFuellstand(double anteil, Color akzent, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = leinwand(img);
        double a = Math.max(0, Math.min(1, anteil));
        // Auch bei 0 bleibt ein Streifen am Boden stehen, sonst sieht die
        // Karte am Anfang aus, als waere das Bild nicht geladen.
        double hoeheAnteil = 0.08 + a * 0.84;
        double pegel = h * (1 - hoeheAnteil);
        double wellenHoehe = h * 0.07;

        java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
        p.moveTo(0, pegel);
        for (int x = 0; x <= w; x += Math.max(1, w / 60)) {
            double y = pegel + Math.sin((double) x / w * Math.PI * 3) * wellenHoehe;
            p.lineTo(x, y);
        }
        p.lineTo(w, h);
        p.lineTo(0, h);
        p.closePath();

        g.setColor(new Color(akzent.getRed(), akzent.getGreen(), akzent.getBlue(), 90));
        g.translate(0, h * 0.05);
        g.fill(p);
        g.translate(0, -h * 0.05);
        g.setColor(akzent);
        g.fill(p);
        g.dispose();
        return img;
    }

    /**
     * Foto als Streifen, mittig zugeschnitten und links abgedunkelt.
     *
     * <p>Ueber dem Streifen liegen die Felder des Passes. Ohne die dunkle
     * Seite verschwindet weisse Schrift auf hellen Stellen des Fotos.
     */
    static BufferedImage renderFoto(BufferedImage foto, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = leinwand(img);

        double faktor = Math.max((double) w / foto.getWidth(), (double) h / foto.getHeight());
        int bw = Math.max(1, (int) Math.round(foto.getWidth() * faktor));
        int bh = Math.max(1, (int) Math.round(foto.getHeight() * faktor));
        g.drawImage(foto, (w - bw) / 2, (h - bh) / 2, bw, bh, null);

        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(0, 0, 0, 170),
                w * 0.7f, 0, new Color(0, 0, 0, 0)));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private void createTextLogo(String text, String bgColor, int width, int height,
                                String outputPath) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, height / 2));
        FontMetrics fm = g.getFontMetrics();
        String displayText = text.length() > 15 ? text.substring(0, 13) + ".." : text;
        int x = (width - fm.stringWidth(displayText)) / 2;
        int y = (height + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(displayText, Math.max(0, x), y);
        g.dispose();
        ImageIO.write(img, "PNG", Paths.get(outputPath).toFile());
    }

    private void createColorIcon(String bgColor, int size, String outputPath) throws IOException {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(hexToColor(bgColor));
        g.fillRect(0, 0, size, size);
        g.dispose();
        ImageIO.write(img, "PNG", Paths.get(outputPath).toFile());
    }

    private BufferedImage resizeImage(BufferedImage original, int maxWidth, int maxHeight) {
        double aspectOriginal = (double) original.getWidth() / original.getHeight();
        double aspectTarget = (double) maxWidth / maxHeight;

        int targetWidth = maxWidth;
        int targetHeight = maxHeight;

        if (aspectOriginal > aspectTarget) {
            targetHeight = (int) (maxWidth / aspectOriginal);
        } else {
            targetWidth = (int) (maxHeight * aspectOriginal);
        }

        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        return resized;
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private Color hexToColor(String hex) {
        if (hex == null || !hex.startsWith("#")) return new Color(60, 52, 137);
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            return new Color(r, g, b);
        } catch (Exception e) {
            return new Color(60, 52, 137);
        }
    }

    private String hexToRgb(String hex) {
        if (hex == null || !hex.startsWith("#")) return "rgb(60,52,137)";
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            return "rgb(%d,%d,%d)".formatted(r, g, b);
        } catch (Exception e) {
            return "rgb(60,52,137)";
        }
    }
}