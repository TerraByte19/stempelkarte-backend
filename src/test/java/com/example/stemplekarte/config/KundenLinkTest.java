package com.example.stemplekarte.config;


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
    }

    @Test
    void mitEinstellung_kurzerLinkAufEigenerAdresse() {
        KundenLink.setze("https://karte.stampit-app.de/");
        assertThat(KundenLink.aktiv()).isTrue();
        assertThat(KundenLink.basis(SERVER)).isEqualTo("https://karte.stampit-app.de");
        assertThat(KundenLink.anmeldung(SERVER, "CARD-04C2BFD8")).isEqualTo("https://karte.stampit-app.de/CARD-04C2BFD8");
    }

    /**
     * Der NFC-Tag muss auf dieselbe Adresse zeigen, auf der sich der Kunde
     * angemeldet hat - nur dort kennt sein Handy ihn (Cookie je Adresse).
     */
    @Test
    void nfcLink_folgtDerKundenadresse() {
        assertThat(KundenLink.nfc(SERVER, "tok1")).isEqualTo(SERVER + "/t/tok1");
        KundenLink.setze("https://karte.stampit-app.de");
        assertThat(KundenLink.nfc(SERVER, "tok1")).isEqualTo("https://karte.stampit-app.de/t/tok1");
    }

    @Test
    void istKundenHost_nurDieEigeneAdresse() {
        assertThat(KundenLink.istKundenHost("stempelkarte-backend.onrender.com")).isTrue();
        KundenLink.setze("https://karte.stampit-app.de");
        assertThat(KundenLink.istKundenHost("karte.stampit-app.de")).isTrue();
        assertThat(KundenLink.istKundenHost("KARTE.stampit-app.de")).isTrue();
        assertThat(KundenLink.istKundenHost("stempelkarte-backend.onrender.com")).isFalse();
        assertThat(KundenLink.istKundenHost(null)).isFalse();
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
