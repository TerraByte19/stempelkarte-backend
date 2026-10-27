package com.example.stemplekarte.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Weisse Schrift auf einer weissen Karte war unlesbar. Die Kundenseiten
 * waehlen jetzt Weiss oder Dunkel, je nach Kartenfarbe.
 */
class KartenKontrastTest {

    private static final String WEISS = "255,255,255";
    private static final String DUNKEL = "26,26,46";

    @Test
    void helleKartenBekommenDunkleSchrift() {
        assertThat(LandingController.inkFuer("#FFFFFF")).isEqualTo(DUNKEL);
        assertThat(LandingController.inkFuer("#fff")).isEqualTo(DUNKEL);
        assertThat(LandingController.inkFuer("#F5E6C8")).isEqualTo(DUNKEL);
        assertThat(LandingController.inkFuer("#FAC875")).isEqualTo(DUNKEL);
    }

    @Test
    void dunkleKartenBehaltenWeisseSchrift() {
        assertThat(LandingController.inkFuer("#3C3489")).isEqualTo(WEISS);
        assertThat(LandingController.inkFuer("#000000")).isEqualTo(WEISS);
        assertThat(LandingController.inkFuer("#2F4A35")).isEqualTo(WEISS);
    }

    @Test
    void kaputteFarbeFaelltAufWeiss() {
        assertThat(LandingController.inkFuer(null)).isEqualTo(WEISS);
        assertThat(LandingController.inkFuer("red; } body {")).isEqualTo(WEISS);
    }

    @Test
    void variableLandetAmBody() {
        assertThat(LandingController.mitKontrast("<html><body><div class=\"card\"></div></body></html>", "#FFFFFF"))
                .contains("<body style=\"--ink: 26,26,46; --ink-gegen: 255,255,255\">");
    }

    private static final String SEITE = "<html><head></head><body><div class=\"shop-name\">X</div></body></html>";

    @Test
    void eigeneNamensFarbe_landetAlsRegel() {
        assertThat(LandingController.mitKontrast(SEITE, "#000000", "#FF8800"))
                .contains("<style>.shop-name { color: #FF8800; }</style></head>")
                .contains("--ink: 255,255,255");
    }

    @Test
    void ohneOderKaputteNamensFarbe_keineRegel() {
        assertThat(LandingController.mitKontrast(SEITE, "#000000", null)).doesNotContain(".shop-name {");
        assertThat(LandingController.mitKontrast(SEITE, "#000000", "red;}body{opacity:0"))
                .doesNotContain("opacity");
    }
}
