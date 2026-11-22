package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.Tarif;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vorschau der Filial-Liste: liest beide Schreibweisen, speichert nichts, geht im Test nie ins Netz. */
class FilialImportServiceTest {

    private Shop kette;
    private List<String> gesucht;
    private FilialImportService dienst;

    @BeforeEach
    void setUp() {
        kette = Shop.create("z@kette.de", "hash", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        gesucht = new ArrayList<>();
        Geocoder geocoder = adresse -> {
            gesucht.add(adresse);
            return adresse.contains("Nirgendwo")
                    ? Optional.empty()
                    : Optional.of(new Geocoder.Punkt(52.52, 13.405, "Gefunden: " + adresse));
        };
        dienst = new FilialImportService(geocoder, 0);
    }

    @Test
    void adresse_wirdGesucht_nameIstAllesVorDemErstenKomma() {
        List<FilialImportService.Zeile> z = dienst.vorschau(kette, "Mitte, Hauptstr. 5, 10115 Berlin\n");
        assertThat(z).hasSize(1);
        assertThat(z.get(0).label()).isEqualTo("Mitte");
        assertThat(z.get(0).adresse()).isEqualTo("Hauptstr. 5, 10115 Berlin");
        assertThat(z.get(0).latitude()).isEqualTo(52.52);
        assertThat(z.get(0).gefunden()).startsWith("Gefunden");
        assertThat(z.get(0).fehler()).isNull();
        assertThat(gesucht).containsExactly("Hauptstr. 5, 10115 Berlin");
    }

    @Test
    void koordinaten_brauchenKeineSuche_kommaAlsDezimalzeichenGeht() {
        List<FilialImportService.Zeile> z = dienst.vorschau(kette, "Nord; 52,60; 13.40");
        assertThat(z.get(0).label()).isEqualTo("Nord");
        assertThat(z.get(0).latitude()).isEqualTo(52.60);
        assertThat(z.get(0).longitude()).isEqualTo(13.40);
        assertThat(z.get(0).fehler()).isNull();
        assertThat(gesucht).isEmpty();
    }

    @Test
    void fehlerBleibenJeZeileStehen_derRestLaeuftWeiter() {
        List<FilialImportService.Zeile> z = dienst.vorschau(kette, String.join("\n",
                "Mitte, Hauptstr. 5, Berlin",
                "",
                "OhneKomma",
                "Geist, Nirgendwo 1",
                "Kaputt; 91; 13",
                "Halb; 52",
                ", Adresse ohne Name",
                "Leer,   ",
                "x".repeat(41) + ", Weg 1"));
        assertThat(z).hasSize(8);
        assertThat(z).extracting(FilialImportService.Zeile::nr).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(z.get(0).fehler()).isNull();
        for (int i = 1; i < 8; i++) {
            assertThat(z.get(i).fehler()).as("Zeile " + (i + 1)).isNotBlank();
            assertThat(z.get(i).latitude()).isNull();
        }
        assertThat(z.get(2).fehler()).contains("nicht gefunden");
    }

    @Test
    void nurKette_nichtLeer_hoechstens40() {
        Shop pro = Shop.create("p@laden.de", "hash", "Einzelladen", 3);
        assertThatThrownBy(() -> dienst.vorschau(pro, "Mitte, Weg 1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Kette");
        assertThatThrownBy(() -> dienst.vorschau(kette, " \n \n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.vorschau(kette, null))
                .isInstanceOf(IllegalArgumentException.class);
        String zuViel = "Filiale; 52; 13\n".repeat(FilialImportService.MAX_ZEILEN + 1);
        assertThatThrownBy(() -> dienst.vorschau(kette, zuViel))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("40");
    }

    @Test
    void riesigerText_wirdAbgewiesen_ohneEineSuche() {
        String riesig = "Mitte, Weg 1\n" + "x".repeat(FilialImportService.MAX_ZEICHEN);
        assertThatThrownBy(() -> dienst.vorschau(kette, riesig))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(gesucht).isEmpty();
    }

    @Test
    void antwortDesKartendienstes_wirdGelesen() {
        Optional<Geocoder.Punkt> p = NominatimGeocoder.lies(
                "[{\"lat\":\"52.5170365\",\"lon\":\"13.3888599\",\"display_name\":\"Berlin, Deutschland\"}]");
        assertThat(p).isPresent();
        assertThat(p.get().latitude()).isEqualTo(52.5170365);
        assertThat(p.get().longitude()).isEqualTo(13.3888599);
        assertThat(p.get().anzeige()).isEqualTo("Berlin, Deutschland");
        assertThat(NominatimGeocoder.lies("[]")).isEmpty();
        assertThat(NominatimGeocoder.lies("kein json")).isEmpty();
        assertThat(NominatimGeocoder.lies("[{\"lat\":\"abc\",\"lon\":\"1\"}]")).isEmpty();
        assertThat(NominatimGeocoder.lies("[{\"lat\":\"95\",\"lon\":\"1\"}]")).isEmpty();
    }
}
