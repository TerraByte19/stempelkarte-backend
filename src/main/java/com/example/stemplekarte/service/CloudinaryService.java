package com.example.stemplekarte.wallet;

import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import com.cloudinary.utils.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Map;

/**
 * Lädt Bilder zu Cloudinary hoch und formatiert sie automatisch passend
 * für Apple/Google Wallet. Bilder überleben so jeden Render-Deploy.
 *
 * Offizielle Wallet-Größen (recherchiert):
 * - Logo:   quadratisch, 660x660px (für beide Wallets optimal)
 * - Hero:   3:1 Banner, 1125x369px (kein Text)
 * - Stempel-Icon: quadratisch, 400x400px (transparent)
 */
@Service
public class CloudinaryService {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryService.class);

    private final Cloudinary cloudinary;

    public CloudinaryService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    public enum ImageType { LOGO, HERO, STAMP }

    /**
     * Lädt ein Base64-Bild hoch, formatiert es passend zum Typ und gibt die
     * öffentliche HTTPS-URL zurück.
     *
     * @param base64    reines Base64 (ohne data:-Präfix)
     * @param publicId  eindeutiger Name, z.B. "logo-CARD-1234"
     * @param type      bestimmt Größe und Zuschnitt
     */
    public String upload(String base64, String publicId, ImageType type) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);

            Transformation<?> transformation = switch (type) {
                // NEU: Schneidet das Logo als perfekten Kreis aus und macht die Ecken transparent
                case LOGO -> new Transformation<>()
                        .width(660).height(660)
                        .crop("pad")                    // Passt das ganze Logo an, ohne es abzuschneiden
                        .background("transparent")      // Innenraum ist durchsichtig (nimmt die schwarze Kartenfarbe an)
                        .border("12px_solid_white")     // HIER ENTSTEHT DER WEIßE RING (12 Pixel dick)
                        .radius("max")                  // Macht das Ganze zu einem perfekten Kreis
                        .quality("auto").fetchFormat("png");
                case HERO -> new Transformation<>()
                        .width(1125).height(369).crop("fill").gravity("center")
                        .quality("auto").fetchFormat("png");

                case STAMP -> new Transformation<>()
                        .width(400).height(400).crop("fit")
                        .quality("auto").fetchFormat("png");
            };

            String folder = switch (type) {
                case LOGO  -> "stampit/logos";
                case HERO  -> "stampit/heroes";
                case STAMP -> "stampit/stamps";
            };

            Map<?, ?> result = cloudinary.uploader().upload(bytes, ObjectUtils.asMap(
                    "public_id", publicId,
                    "folder", folder,
                    "overwrite", true,
                    "invalidate", true,
                    "transformation", transformation,
                    "resource_type", "image"
            ));

            String url = (String) result.get("secure_url");
            log.info("Cloudinary Upload OK ({}): {}", type, url);
            return url;
        } catch (Exception e) {
            log.error("Cloudinary Upload fehlgeschlagen ({}): {}", type, e.getMessage());
            throw new RuntimeException("Bild-Upload fehlgeschlagen: " + e.getMessage(), e);
        }
    }
}