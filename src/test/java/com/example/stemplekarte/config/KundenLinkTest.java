package com.example.stemplekarte.config;

import com.example.stemplekarte.service.EmpfehlungService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Kundenadresse: ohne Einstellung alles wie bisher, mit Einstellung kurze Links. */
class KundenLinkTest {

    private static final String SERVER = "https://server.test";

    @AfterEach
    void zuruecksetzen() {
        KundenLink.setze(null);
    }

    @Test
    void ohneEinstellung_bleibtAllesWieBisher() {
        KundenLink.setze("");
        assertThat(KundenLink.aktiv()).isFalse();
        assertThat(KundenLink.basis(SERVER)).isEqualTo(SERVER);
        assertThat(KundenLink.anmeldung(SERVER, "CARD-04C2BFD8")).isEqualTo(SERVER + "/karte-neu/CARD-04C2BFD8");
        assertThat(KundenLink.anmeldePfad("CARD-04C2BFD8")).isEqualTo("/karte-neu/CARD-04C2BFD8");
        assertThat(EmpfehlungService.linkFuer(SERVER, "CARD-1", "ABC234")).isEqualTo(SERVER + "/karte-neu/CARD-1?ref=ABC234");
    }

    @Test
    void mitEinstellung_kurzerLinkAufEigenerAdresse() {
        KundenLink.setze("https://karte.stampit-app.de/");
        assertThat(KundenLink.aktiv()).isTrue();
        assertThat(KundenLink.basis(SERVER)).isEqualTo("https://karte.stampit-app.de");
        assertThat(KundenLink.anmeldung(SERVER, "CARD-04C2BFD8")).isEqualTo("https://karte.stampit-app.de/CARD-04C2BFD8");
        assertThat(EmpfehlungService.linkFuer(SERVER, "CARD-1", "ABC234"))
                .isEqualTo("https://karte.stampit-app.de/CARD-1?ref=ABC234");
    }

    @Test
    void ungewohnteKartenId_behaeltDenLangenPfad() {
        KundenLink.setze("https://karte.stampit-app.de");
        // Die Kurz-Route nimmt nur CARD-...; alles andere muss ueber /karte-neu/ laufen.
        assertThat(KundenLink.anmeldung(SERVER, "demo")).isEqualTo("https://karte.stampit-app.de/karte-neu/demo");
    }

    @Test
    void unbrauchbareEinstellung_wirdIgnoriert() {
        for (String kaputt : new String[]{"karte.stampit-app.de", "javascript:alert(1)", "https://a.de/pfad", "https://a.de?x=1", "  "}) {
            KundenLink.setze(kaputt);
            assertThat(KundenLink.aktiv()).as(kaputt).isFalse();
        }
    }
}
