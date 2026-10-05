package com.example.stemplekarte.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Der Server laedt die Bild-Adressen eines Ladens selbst herunter, wenn er
 * den Pass baut. Ohne Pruefung ist das eine Fernsteuerung: ein angemeldeter
 * Laden traegt eine interne Adresse ein, der Server ruft sie ab, und das
 * Ergebnis steht sichtbar auf der Wallet-Karte.
 *
 * Gefunden hat das die Sicherheitspruefung am Commit mit dem Streifenbild;
 * dieselbe Luecke stand schon laenger bei Logo, Banner und Stempel-Bild.
 */
class BildUrlTest {

    /** Loest jeden Namen auf die angegebene Adresse auf - kein echtes DNS im Test. */
    private BildUrl.Aufloeser zeigtAuf(String ip) {
        return host -> new InetAddress[]{InetAddress.getByName(ip)};
    }

    @Test
    void oeffentlicheHttpsAdresseIstErlaubt() {
        assertThat(BildUrl.istErlaubt("https://res.cloudinary.com/demo/image/upload/logo.png",
                zeigtAuf("104.18.1.1"))).isTrue();
    }

    @Test
    void httpIstNichtErlaubt() {
        assertThat(BildUrl.istErlaubt("http://res.cloudinary.com/logo.png",
                zeigtAuf("104.18.1.1"))).isFalse();
    }

    /**
     * Die eigentliche Gefahr: Adressen, die nur von innen erreichbar sind.
     * 169.254.169.254 ist die Metadaten-Adresse vieler Hosting-Umgebungen -
     * dort liegen Zugangsdaten.
     */
    @Test
    void interneAdressenSindGesperrt() {
        String[] intern = {
                "127.0.0.1",        // der eigene Dienst
                "169.254.169.254",  // Metadaten der Hosting-Umgebung
                "10.0.0.5",         // privates Netz
                "172.16.4.2",
                "192.168.1.10",
                "100.64.0.1",       // Carrier-Grade-NAT
                "0.0.0.0",
        };
        for (String ip : intern) {
            assertThat(BildUrl.istErlaubt("https://bild.example/x.png", zeigtAuf(ip)))
                    .as("Adresse %s", ip).isFalse();
        }
    }

    @Test
    void interneIPv6AdressenSindGesperrt() {
        for (String ip : new String[]{"::1", "fd00::1", "fe80::1"}) {
            assertThat(BildUrl.istErlaubt("https://bild.example/x.png", zeigtAuf(ip)))
                    .as("Adresse %s", ip).isFalse();
        }
    }

    /** Auch als Zahl direkt in der Adresse, nicht nur ueber einen Namen. */
    @Test
    void interneAdresseDirektInDerUrl() {
        assertThat(BildUrl.istErlaubt("https://169.254.169.254/latest/meta-data/",
                zeigtAuf("169.254.169.254"))).isFalse();
    }

    /**
     * https://interner-host@fremder-host/ - manche Bibliotheken lesen den
     * Teil vor dem @ als Ziel, die Pruefung sonst den Teil dahinter.
     */
    @Test
    void adresseMitBenutzerteilIstGesperrt() {
        assertThat(BildUrl.istErlaubt("https://169.254.169.254@cloudinary.com/x.png",
                zeigtAuf("104.18.1.1"))).isFalse();
    }

    @Test
    void unsinnIstGesperrt() {
        for (String url : new String[]{null, "", "   ", "file:///etc/passwd",
                "ftp://host/x.png", "javascript:alert(1)", "https://", "nur-text"}) {
            assertThat(BildUrl.istErlaubt(url, zeigtAuf("104.18.1.1")))
                    .as("Eingabe %s", url).isFalse();
        }
    }

    @Test
    void unbekannterNameIstGesperrt() {
        assertThat(BildUrl.istErlaubt("https://gibtesnicht.example/x.png",
                host -> { throw new java.net.UnknownHostException(host); })).isFalse();
    }

    // ── pruefe(): das, was die Controller aufrufen ────────────────────────

    @Test
    void pruefeLaesstLeeresDurch() {
        // Leer heisst "Bild entfernen" - das muss moeglich bleiben.
        assertThatCode(() -> BildUrl.pruefe(null, "logoUrl")).doesNotThrowAnyException();
        assertThatCode(() -> BildUrl.pruefe("", "logoUrl")).doesNotThrowAnyException();
    }

    @Test
    void pruefeMeldetDasFeld() {
        assertThatThrownBy(() -> BildUrl.pruefe("http://127.0.0.1/x.png", "stripImageUrl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stripImageUrl");
    }
}
