package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.RewardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prueft den ganzen Weg: Karte mit Stil rein, Dateien im Pass-Ordner raus.
 *
 * StreifenStilTest prueft die Zeichenroutinen einzeln. Hier geht es um das
 * Stueck davor - die Verteilung nach Stil und das Schreiben der drei
 * Dateien. Ein Fehler dort faellt in den Zeichen-Tests nicht auf: die
 * Grafik waere richtig, nur kaeme sie nie im Pass an.
 */
class StreifenDateienTest {

    private PassTemplateGenerator generator(Path ordner) {
        PassTemplateGenerator gen = new PassTemplateGenerator(mock(RewardService.class));
        ReflectionTestUtils.setField(gen, "uploadPath", ordner.toString());
        ReflectionTestUtils.setField(gen, "passTypeIdentifier", "pass.com.test");
        ReflectionTestUtils.setField(gen, "teamIdentifier", "TEAM123");
        return gen;
    }

    private CustomerCard karte(String stil) {
        Shop shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Testladen");
        when(shop.getColorBackground()).thenReturn("#3C3489");
        when(shop.getColorForeground()).thenReturn("#FFFFFF");
        when(shop.getColorLabel()).thenReturn("#FAC875");

        Card card = Card.create(shop, "Kaffeekarte", "Beschreibung", 10, "Gratis Kaffee");
        card.updateDesign(stil, "preset", "coffee", "#FAC875", "number");

        Customer kunde = mock(Customer.class);
        when(kunde.getId()).thenReturn("CUST-1");
        CustomerCard cc = CustomerCard.create(kunde, card);
        for (int i = 0; i < 4; i++) cc.addStamp();
        return cc;
    }

    private void pruefeStreifen(Path pfad) throws Exception {
        String[] namen = {"strip.png", "strip@2x.png", "strip@3x.png"};
        int[][] masse = {{375, 144}, {750, 288}, {1125, 432}};
        for (int i = 0; i < namen.length; i++) {
            Path datei = pfad.resolve(namen[i]);
            assertThat(Files.exists(datei)).as(namen[i]).isTrue();
            var img = ImageIO.read(datei.toFile());
            assertThat(img.getWidth()).as(namen[i] + " Breite").isEqualTo(masse[i][0]);
            assertThat(img.getHeight()).as(namen[i] + " Hoehe").isEqualTo(masse[i][1]);
        }
    }

    @Test
    void jederStreifenStilSchreibtDreiDateien(@TempDir Path ordner) throws Exception {
        for (String stil : List.of("grid", "balken", "ring", "fuellstand")) {
            PassTemplateGenerator gen = generator(ordner.resolve(stil));
            Path pfad = Path.of(gen.generateTemplate(karte(stil)));
            pruefeStreifen(pfad);
        }
    }

    /**
     * Foto-Stil ohne Bild: der Balken springt ein. Ohne diesen Rueckfall
     * fehlte strip.png ganz, und iOS zeigte eine Karte mit Loch an der
     * Stelle, wo die Felder sitzen.
     */
    @Test
    void fotoOhneBildSchreibtTrotzdemEinenStreifen(@TempDir Path ordner) throws Exception {
        PassTemplateGenerator gen = generator(ordner);
        Path pfad = Path.of(gen.generateTemplate(karte("foto")));
        pruefeStreifen(pfad);
    }

    /** Stil "number" hat keinen Streifen - dort darf auch keiner liegen. */
    @Test
    void zahlenStilSchreibtKeinenStreifen(@TempDir Path ordner) throws Exception {
        PassTemplateGenerator gen = generator(ordner);
        Path pfad = Path.of(gen.generateTemplate(karte("number")));

        assertThat(Files.exists(pfad.resolve("strip.png"))).isFalse();
        // Icon und Logo gehoeren trotzdem dazu.
        assertThat(Files.exists(pfad.resolve("icon@3x.png"))).isTrue();
        assertThat(ImageIO.read(pfad.resolve("icon@3x.png").toFile()).getWidth()).isEqualTo(114);
        assertThat(Files.exists(pfad.resolve("logo@3x.png"))).isTrue();
    }

    /**
     * Ein Stilwechsel muss die alten Dateien loswerden. Bleibt ein alter
     * Streifen liegen, zeigt die Karte weiter das vorige Design.
     */
    @Test
    void wechselAufZahlenRaeumtDenAltenStreifenWeg(@TempDir Path ordner) throws Exception {
        PassTemplateGenerator gen = generator(ordner);
        CustomerCard cc = karte("balken");
        Path pfad = Path.of(gen.generateTemplate(cc));
        assertThat(Files.exists(pfad.resolve("strip.png"))).isTrue();

        cc.getCard().updateDesign("number", null, null, null, null);
        gen.generateTemplate(cc);

        assertThat(Files.exists(pfad.resolve("strip.png"))).isFalse();
        assertThat(Files.exists(pfad.resolve("strip@3x.png"))).isFalse();
    }
}
