package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Shop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class PassTemplateGenerator {

    @Value("${stempelkarte.upload-path:./uploads}")
    private String uploadPath;

    public String generateTemplate(Shop shop) throws IOException {
        // Ordner pro Shop
        String shopDir = uploadPath + "/pass-templates/" + shop.getId() + ".pass";
        Path templatePath = Paths.get(shopDir);
        Files.createDirectories(templatePath);

        // pass.json schreiben
        String passJson = generatePassJson(shop);
        Files.writeString(templatePath.resolve("pass.json"), passJson);

        // Logo generieren (aus URL laden oder Placeholder erstellen)
        generateLogoImages(shop, templatePath);

        // Icon generieren
        generateIconImages(shop, templatePath);

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

    private void generateLogoImages(Shop shop, Path templatePath) throws IOException {
        // Logo von URL laden falls vorhanden, sonst Text-Placeholder
        if (shop.getLogoUrl() != null && !shop.getLogoUrl().isBlank()) {
            try {
                BufferedImage logo = ImageIO.read(new URL(shop.getLogoUrl()));
                // 1x (160x50)
                BufferedImage logo1x = resizeImage(logo, 160, 50);
                ImageIO.write(logo1x, "PNG", templatePath.resolve("logo.png").toFile());
                // 2x (320x100)
                BufferedImage logo2x = resizeImage(logo, 320, 100);
                ImageIO.write(logo2x, "PNG", templatePath.resolve("logo@2x.png").toFile());
                return;
            } catch (Exception e) {
                // Fallback auf Text-Placeholder
            }
        }
        // Text-Placeholder mit Shop-Namen
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

        // Transparenter Hintergrund
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, width, height);

        // Text
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, height / 2));
        FontMetrics fm = g.getFontMetrics();
        int x = (width - fm.stringWidth(text)) / 2;
        int y = (height + fm.getAscent() - fm.getDescent()) / 2;
        // Text kürzen falls zu lang
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
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
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