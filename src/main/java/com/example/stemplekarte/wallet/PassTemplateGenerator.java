package com.example.stemplekarte.wallet;

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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class PassTemplateGenerator {

    @Value("${stempelkarte.upload-path:./uploads}")
    private String uploadPath;

    public String generateTemplate(CustomerCard cc) throws IOException {
        Shop shop = cc.getCard().getShop();
        int stamps = cc.getStamps();
        int threshold = cc.getCard().getRewardThreshold();

        // Ordner pro Kundenkarte (damit jede Karte ihr eigenes Stempelbild hat)
        String shopDir = uploadPath + "/pass-templates/" + cc.getId() + ".pass";
        Path templatePath = Paths.get(shopDir);
        Files.createDirectories(templatePath);

        Files.writeString(templatePath.resolve("pass.json"), generatePassJson(shop));
        generateLogoImages(shop, templatePath);
        generateIconImages(shop, templatePath);

        // Altes Stempelbild entfernen, dann nur bei Raster-Stil neu erzeugen
        deleteStripImages(templatePath);
        if ("grid".equalsIgnoreCase(shop.getWalletStyle())) {
            generateStripImages(shop, stamps, threshold, templatePath);
        }

        return shopDir;
    }

    private String generatePassJson(Shop shop) {
        return """
                {
                  "formatVersion": 1,
                  "passTypeIdentifier": "pass.com.example.stempelkarte",
                  "teamIdentifier": "ABCDE12345",
                  "organizationName": "%s",
                  "description": "Treuekarte",
                  "logoText": "%s",
                  "foregroundColor": "rgb(255,255,255)",
                  "backgroundColor": "%s",
                  "labelColor": "rgb(255,200,100)",
                  "storeCard": {}
                }
                """.formatted(
                shop.getName(),
                shop.getName(),
                hexToRgb(shop.getColorBackground())
        );
    }

    // ===================== STEMPEL-RASTER (STRIP) =====================

    private void generateStripImages(Shop shop, int stamps, int threshold, Path templatePath) throws IOException {
        BufferedImage customIcon = loadCustomIcon(shop);
        ImageIO.write(renderStrip(shop, stamps, threshold, 320, 110, customIcon), "PNG",
                templatePath.resolve("strip.png").toFile());
        ImageIO.write(renderStrip(shop, stamps, threshold, 640, 220, customIcon), "PNG",
                templatePath.resolve("strip@2x.png").toFile());
        ImageIO.write(renderStrip(shop, stamps, threshold, 960, 330, customIcon), "PNG",
                templatePath.resolve("strip@3x.png").toFile());
    }

    private BufferedImage loadCustomIcon(Shop shop) {
        if ("upload".equalsIgnoreCase(shop.getStampIconType())
                && shop.getStampIconUrl() != null && !shop.getStampIconUrl().isBlank()) {
            try {
                return ImageIO.read(new URL(shop.getStampIconUrl()));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private BufferedImage renderStrip(Shop shop, int stamps, int threshold,
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

        Color stampColor = hexToColor(shop.getStampColor() != null
                ? shop.getStampColor() : shop.getColorLabel());
        String emptyStyle = shop.getEmptyStampStyle() == null ? "number" : shop.getEmptyStampStyle();
        boolean useCustom = "upload".equalsIgnoreCase(shop.getStampIconType()) && customIcon != null;
        String preset = shop.getStampPreset() == null ? "coffee" : shop.getStampPreset();

        for (int i = 0; i < threshold; i++) {
            int r = i / cols, c = i % cols;
            int itemsInRow = Math.min(cols, threshold - r * cols);
            double rowOffset = (cols - itemsInRow) * cellW / 2.0;
            double cx = padX + rowOffset + c * cellW + cellW / 2;
            double cy = padY + r * cellH + cellH / 2;
            boolean filled = i < stamps;
            drawStamp(g, filled, i + 1, cx, cy, diameter, useCustom, customIcon,
                    preset, stampColor, emptyStyle);
        }

        g.dispose();
        return img;
    }

    private void drawStamp(Graphics2D g, boolean filled, int number, double cx, double cy,
                           double d, boolean useCustom, BufferedImage customIcon,
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
                g.setColor(new Color(255, 255, 255, 110));
                g.setStroke(new BasicStroke((float) Math.max(2, d * 0.045)));
                g.draw(new Ellipse2D.Double(cx - r, cy - r, d, d));
                g.setColor(new Color(255, 255, 255, 175));
                g.setFont(new Font("Arial", Font.BOLD, (int) (d * 0.42)));
                FontMetrics fm = g.getFontMetrics();
                String s = String.valueOf(number);
                g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0),
                        (float) (cy + fm.getAscent() / 2.0 - fm.getDescent() / 2.0));
            } else { // faded
                g.setColor(new Color(255, 255, 255, 60));
                g.fill(new Ellipse2D.Double(cx - r, cy - r, d, d));
                if (useCustom) {
                    drawImageInCircle(g, customIcon, cx, cy, d * 0.92, 0.45f);
                } else {
                    drawPreset(g, preset, cx, cy, d * 0.55, new Color(255, 255, 255), 120);
                }
            }
        }
    }

    private void drawImageInCircle(Graphics2D g, BufferedImage img, double cx, double cy,
                                   double d, float alpha) {
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
            case "star" -> g.fill(starShape(cx, cy, size / 2));
            case "heart" -> g.fill(heartShape(cx, cy, size / 2));
            case "square" -> {
                double s = size * 0.86;
                g.fill(new RoundRectangle2D.Double(cx - s / 2, cy - s / 2, s, s, s * 0.22, s * 0.22));
            }
            case "dot" -> g.fill(new Ellipse2D.Double(cx - size / 2, cy - size / 2, size, size));
            default -> drawCoffee(g, cx, cy, size, col);
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
            try {
                Files.deleteIfExists(templatePath.resolve(n));
            } catch (IOException ignored) {
            }
        }
    }

    // ===================== LOGO / ICON (unverändert) =====================

    private void generateLogoImages(Shop shop, Path templatePath) throws IOException {
        if (shop.getLogoUrl() != null && !shop.getLogoUrl().isBlank()) {
            try {
                BufferedImage logo = ImageIO.read(new URL(shop.getLogoUrl()));
                ImageIO.write(resizeImage(logo, 160, 50), "PNG", templatePath.resolve("logo.png").toFile());
                ImageIO.write(resizeImage(logo, 320, 100), "PNG", templatePath.resolve("logo@2x.png").toFile());
                return;
            } catch (Exception e) {
                // Fallback
            }
        }
        createTextLogo(shop.getName(), shop.getColorBackground(), 160, 50,
                templatePath.resolve("logo.png").toString());
        createTextLogo(shop.getName(), shop.getColorBackground(), 320, 100,
                templatePath.resolve("logo@2x.png").toString());
    }

    private void generateIconImages(Shop shop, Path templatePath) throws IOException {
        createColorIcon(shop.getColorBackground(), 29, templatePath.resolve("icon.png").toString());
        createColorIcon(shop.getColorBackground(), 58, templatePath.resolve("icon@2x.png").toString());
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
        int x = (width - fm.stringWidth(text)) / 2;
        int y = (height + fm.getAscent() - fm.getDescent()) / 2;
        String displayText = text.length() > 15 ? text.substring(0, 13) + ".." : text;
        g.drawString(displayText, Math.max(0, x), y);
        g.dispose();
        ImageIO.write(img, "PNG", Paths.get(outputPath).toFile());
    }

    private void createColorIcon(String bgColor, int size, String outputPath) throws IOException {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(hexToColor(bgColor));
        g.fillRoundRect(0, 0, size, size, size / 4, size / 4);
        g.dispose();
        ImageIO.write(img, "PNG", Paths.get(outputPath).toFile());
    }

    private BufferedImage resizeImage(BufferedImage original, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, width, height, null);
        g.dispose();
        return resized;
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