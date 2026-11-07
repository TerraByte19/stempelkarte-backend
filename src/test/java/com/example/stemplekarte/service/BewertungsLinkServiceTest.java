package com.example.stemplekarte.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bewertungslink-Tool: ohne Schluessel aus, Antwort von Google wird zu
 * Treffern mit Link, Ergebnisse kommen 24 h aus dem Speicher, die Drossel
 * zaehlt nur echte Anfragen, Place-IDs werden geprueft, QR ist ein PNG.
 */
class BewertungsLinkServiceTest {

    private static final Instant JETZT = Instant.parse("2026-11-07T22:00:00Z");
    private static final String ANTWORT = """
            {"places":[
              {"id":"ChIJN1t_tDeuEmsRUsoyG83frY4","formattedAddress":"Müllerstr. 1, 13353 Berlin, Deutschland",
               "displayName":{"text":"Café Nord","languageCode":"de"}},
              {"id":"zu kurz","displayName":{"text":"Kaputt"}},
              {"id":"ChIJAbCdEfGhIjKlMnOpQrStUv","formattedAddress":"Hauptstr. 9, 10827 Berlin","displayName":{"text":"Café Nord 2"}}
            ]}""";

    /** Dienst mit Schluessel, Google-Aufruf durch feste Antwort ersetzt, Aufrufe gezaehlt. */
    private static class Test_Dienst extends BewertungsLinkService {
        final List<String> anfragen = new ArrayList<>();
        String antwort = ANTWORT;
        boolean kaputt = false;
        Test_Dienst(String key) { super(key); }
        @Override
        protected String abfragen(String q) throws IOException {
            anfragen.add(q);
            if (kaputt) throw new IOException("HTTP 403: key invalid");
            return antwort;
        }
    }

    @Test
    void ohneSchluessel_aus() {
        BewertungsLinkService d = new BewertungsLinkService("");
        assertThat(d.aktiv()).isFalse();
        BewertungsLinkService.Ergebnis e = d.suche("Café Nord Berlin", "1.1.1.1", JETZT);
        assertThat(e.aktiv()).isFalse();
        assertThat(e.treffer()).isEmpty();
    }

    @Test
    void suche_liefertTrefferMitLink_undNimmtNurGueltigeIds() {
        Test_Dienst d = new Test_Dienst("key");
        BewertungsLinkService.Ergebnis e = d.suche("  Café   Nord\tBerlin ", "1.1.1.1", JETZT);

        assertThat(e.aktiv()).isTrue();
        assertThat(d.anfragen).containsExactly("Café Nord Berlin");      // bereinigt
        assertThat(e.treffer()).hasSize(2);                               // "zu kurz" fliegt raus
        BewertungsLinkService.Treffer t = e.treffer().get(0);
        assertThat(t.placeId()).isEqualTo("ChIJN1t_tDeuEmsRUsoyG83frY4");
        assertThat(t.name()).isEqualTo("Café Nord");
        assertThat(t.adresse()).isEqualTo("Müllerstr. 1, 13353 Berlin, Deutschland");
        assertThat(t.link()).isEqualTo("https://search.google.com/local/writereview?placeid=ChIJN1t_tDeuEmsRUsoyG83frY4");
    }

    @Test
    void cache_24Stunden_unabhaengigVonGrossschreibung() {
        Test_Dienst d = new Test_Dienst("key");
        d.suche("Café Nord", "1.1.1.1", JETZT);
        d.suche("café nord", "2.2.2.2", JETZT.plus(Duration.ofHours(23)));
        assertThat(d.anfragen).hasSize(1);
        d.suche("Café Nord", "1.1.1.1", JETZT.plus(Duration.ofHours(25)));
        assertThat(d.anfragen).hasSize(2);
    }

    @Test
    void drossel_zaehltNurEchteAnfragen() {
        Test_Dienst d = new Test_Dienst("key");
        for (int i = 0; i < BewertungsLinkService.MAX_JE_IP; i++) d.suche("Laden " + i, "9.9.9.9", JETZT.plusSeconds(i));
        assertThatThrownBy(() -> d.suche("Laden neu", "9.9.9.9", JETZT.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(Drossel.TEXT);
        // Aus dem Speicher geht weiter, andere Adresse auch.
        assertThat(d.suche("Laden 1", "9.9.9.9", JETZT.plusSeconds(61)).treffer()).isNotEmpty();
        assertThat(d.suche("Laden neu", "8.8.8.8", JETZT.plusSeconds(62)).treffer()).isNotEmpty();
    }

    @Test
    void tagesdeckel_fuerAlleZusammen_auchBeiGefaelschterIp() {
        Test_Dienst d = new Test_Dienst("key");
        // Jede Anfrage von einer anderen (gefaelschten) Adresse: die Drossel je IP greift nie.
        for (int i = 0; i < BewertungsLinkService.MAX_JE_TAG; i++) d.suche("Laden " + i, "ip-" + i, JETZT.plusSeconds(i));
        assertThat(d.anfragen).hasSize(BewertungsLinkService.MAX_JE_TAG);
        assertThatThrownBy(() -> d.suche("Laden zu viel", "ip-neu", JETZT.plusSeconds(3000)))   // 23:50 Berlin, gleicher Tag
                .isInstanceOf(IllegalArgumentException.class).hasMessage(BewertungsLinkService.TAG_VOLL);
        // Aus dem Speicher geht weiter, und am naechsten Tag (Berlin) zaehlt es neu.
        assertThat(d.suche("Laden 3", "ip-x", JETZT.plusSeconds(3001)).treffer()).isNotEmpty();
        assertThat(d.suche("Laden morgen", "ip-y", JETZT.plus(Duration.ofHours(4))).treffer()).isNotEmpty();  // 02:00 Berlin = neuer Tag
        assertThat(d.anfragen).hasSize(BewertungsLinkService.MAX_JE_TAG + 1);
    }

    @Test
    void zuKurz_400_googleKaputt_503() {
        Test_Dienst d = new Test_Dienst("key");
        assertThatThrownBy(() -> d.suche("ab", "1.1.1.1", JETZT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.suche(null, "1.1.1.1", JETZT)).isInstanceOf(IllegalArgumentException.class);
        d.kaputt = true;
        assertThatThrownBy(() -> d.suche("Café Süd", "1.1.1.1", JETZT)).isInstanceOf(IllegalStateException.class);
        d.kaputt = false;
        d.antwort = "kein json";
        assertThat(d.suche("Café West", "1.1.1.1", JETZT).treffer()).isEmpty();
    }

    @Test
    void placeId_linkUndQr() {
        assertThat(BewertungsLinkService.linkFuer(" ChIJN1t_tDeuEmsRUsoyG83frY4 "))
                .isEqualTo("https://search.google.com/local/writereview?placeid=ChIJN1t_tDeuEmsRUsoyG83frY4");
        assertThatThrownBy(() -> BewertungsLinkService.linkFuer("<script>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BewertungsLinkService.linkFuer("")).isInstanceOf(IllegalArgumentException.class);
        byte[] png = BewertungsLinkService.qrPng("ChIJN1t_tDeuEmsRUsoyG83frY4");
        assertThat(png.length).isGreaterThan(500);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(png[2]).isEqualTo((byte) 'N');
        assertThat(png[3]).isEqualTo((byte) 'G');
    }
}
