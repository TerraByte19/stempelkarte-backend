package com.example.stemplekarte.wallet;

import com.cloudinary.Cloudinary;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Tests für die Bild-Größenprüfung in CloudinaryService.
 * Prüft, dass leere und zu große Bilder abgelehnt werden, BEVOR sie zu
 * Cloudinary hochgeladen werden (Schutz vor Kosten + Server-Last).
 */
class CloudinaryServiceTest {

    @Test
    void leeresBild_wirdAbgelehnt() {
        Cloudinary cloudinary = mock(Cloudinary.class);
        CloudinaryService service = new CloudinaryService(cloudinary);

        assertThatThrownBy(() ->
                service.upload("", "test-id", CloudinaryService.ImageType.NEWSLETTER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kein Bild");
    }

    @Test
    void nullBild_wirdAbgelehnt() {
        Cloudinary cloudinary = mock(Cloudinary.class);
        CloudinaryService service = new CloudinaryService(cloudinary);

        assertThatThrownBy(() ->
                service.upload(null, "test-id", CloudinaryService.ImageType.NEWSLETTER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kein Bild");
    }

    @Test
    void zuGrossesBild_wirdAbgelehnt() {
        Cloudinary cloudinary = mock(Cloudinary.class);
        CloudinaryService service = new CloudinaryService(cloudinary);

        // Ein Base64-String, der für > 5 MB Rohdaten steht.
        // 8 MB an Bytes → als Base64 kodiert.
        byte[] bigData = new byte[8 * 1024 * 1024];
        String bigBase64 = Base64.getEncoder().encodeToString(bigData);

        assertThatThrownBy(() ->
                service.upload(bigBase64, "test-id", CloudinaryService.ImageType.HERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zu groß");
    }
}