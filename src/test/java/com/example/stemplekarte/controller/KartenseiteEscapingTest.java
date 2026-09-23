package com.example.stemplekarte.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Kundenseite wird als HTML-Zeichenkette zusammengebaut, und die Werte
 * darin kommen vom Laden: Ladenname, Kartenname, Praemiennamen, Logo-URL.
 * Dazu der Kundenname.
 *
 * Auf einer Plattform mit mehreren Laeden heisst das: ein Laden koennte
 * Code auf der Kartenseite seiner eigenen Kunden ausfuehren. Genau das war
 * beim Bau der Punkteseite offen - der Katalog wurde escaped, die Zeile mit
 * dem naechsten Ziel daneben nicht. Diese Asymmetrie ist die Art Luecke,
 * die beim Lesen nicht auffaellt.
 *
 * Deshalb steht escapeHtml hier unter Test, und die Katalogliste im
 * Browser wird ueber textContent aufgebaut statt ueber innerHTML - dort
 * gibt es dann gar nichts mehr zu escapen.
 */
class KartenseiteEscapingTest {

    @Test
    void spitzeKlammernWerdenUnschaedlich() {
        assertThat(LandingController.escapeHtml("<script>alert(1)</script>"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<");
    }

    @Test
    void anfuehrungszeichenBrechenNichtAusAttributenAus() {
        // Die Logo-URL steht in einem Attribut mit EINFACHEN Anfuehrungs-
        // zeichen: <img src='...'>. Ein Apostroph darin beendet das Attribut.
        assertThat(LandingController.escapeHtml("x' onerror='alert(1)"))
                .doesNotContain("'")
                .contains("&#39;");

        assertThat(LandingController.escapeHtml("x\" onerror=\"alert(1)"))
                .doesNotContain("\"")
                .contains("&quot;");
    }

    @Test
    void kaufmaennischesUndZuerst() {
        // & muss als erstes ersetzt werden, sonst wird aus "&lt;" ein
        // doppelt kodiertes "&amp;lt;" - oder schlimmer, eine bereits
        // kodierte Eingabe wird wieder lesbar.
        assertThat(LandingController.escapeHtml("&lt;script&gt;"))
                .isEqualTo("&amp;lt;script&amp;gt;");
    }

    @Test
    void harmloserTextBleibtLesbar() {
        assertThat(LandingController.escapeHtml("Gratis-Kaffee")).isEqualTo("Gratis-Kaffee");
        assertThat(LandingController.escapeHtml("Café Nordwind")).isEqualTo("Café Nordwind");
    }

    @Test
    void nullGibtLeerenText() {
        assertThat(LandingController.escapeHtml(null)).isEmpty();
    }

    // ── Farben im CSS ───────────────────────────────────────────────────
    //
    // Die Ladenfarbe landet in einer CSS-Regel: background: %s;
    // escapeHtml reicht dort NICHT - es ersetzt spitze Klammern und
    // Anfuehrungszeichen, aber ein Semikolon beendet die Deklaration und
    // eine geschweifte Klammer die ganze Regel. Genau dieser Griff zum
    // falschen Werkzeug war der Fehler: escapeHtml sah nach Absicherung
    // aus und war keine.

    @Test
    void hexFarbenGehenDurch() {
        assertThat(LandingController.safeCssColor("#3C3489")).isEqualTo("#3C3489");
        assertThat(LandingController.safeCssColor("#fff")).isEqualTo("#fff");
        assertThat(LandingController.safeCssColor("#3C3489AA")).isEqualTo("#3C3489AA");
    }

    @Test
    void ausbruchAusDerCssRegelWirdAbgewiesen() {
        // Der Laden setzt seine Farbe selbst und sie wird nirgends geprueft.
        // 32 Zeichen reichen, um die Kartenseite seiner Kunden umzugestalten.
        assertThat(LandingController.safeCssColor("red;}body{opacity:0")).isEqualTo("#3C3489");
        assertThat(LandingController.safeCssColor("#fff;}*{display:none")).isEqualTo("#3C3489");
        assertThat(LandingController.safeCssColor("url(https://boese.example)"))
                .isEqualTo("#3C3489");
    }

    @Test
    void farbnamenUndLeerwerteFallenAufDenStandardZurueck() {
        // Bewusst streng: nur Hex. "red" waere harmlos, aber jede Ausnahme
        // macht die Pruefung angreifbarer, und die Oberflaeche liefert
        // ohnehin nur Hex.
        assertThat(LandingController.safeCssColor("red")).isEqualTo("#3C3489");
        assertThat(LandingController.safeCssColor("")).isEqualTo("#3C3489");
        assertThat(LandingController.safeCssColor(null)).isEqualTo("#3C3489");
        assertThat(LandingController.safeCssColor("#xyz")).isEqualTo("#3C3489");
    }
}
