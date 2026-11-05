package com.example.stemplekarte.i18n;

import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Kundentexte: jede Sprache hat jeden Schluessel, Platzhalter stimmen, Rueckfall ist Deutsch. */
class TextsTest {

    private static final Pattern PLATZHALTER = Pattern.compile("\\{\\d\\}");

    /** Welche Platzhalter vorkommen; die Reihenfolge darf je Sprache anders sein. */
    private static java.util.Set<String> platzhalter(String s) {
        java.util.Set<String> p = new java.util.TreeSet<>();
        Matcher m = PLATZHALTER.matcher(s);
        while (m.find()) p.add(m.group());
        return p;
    }

    @Test
    void jedeSpracheHatJedenSchluessel_undDiePlatzhalter() {
        Map<String, String> de = Texts.alle("de");
        assertThat(de).isNotEmpty();
        for (String lang : Texts.SPRACHEN) {
            Map<String, String> texte = Texts.alle(lang);
            assertThat(texte.keySet()).as(lang).containsExactlyInAnyOrderElementsOf(de.keySet());
            for (var e : de.entrySet()) {
                String wert = texte.get(e.getKey());
                assertThat(wert).as(lang + "." + e.getKey()).isNotBlank();
                assertThat(platzhalter(wert)).as(lang + "." + e.getKey() + " Platzhalter")
                        .isEqualTo(platzhalter(e.getValue()));
                // Hausregel: normale Bindestriche, kein Geviertstrich.
                assertThat(wert).as(lang + "." + e.getKey()).doesNotContain("—");
            }
        }
    }

    @Test
    void t_setztPlatzhalterEin_undFaelltAufDeutschZurueck() {
        assertThat(Texts.t("de", "stamp.progress", 3, 10)).isEqualTo("3 von 10 Stempeln");
        assertThat(Texts.t("en", "stamp.progress", 3, 10)).isEqualTo("3 of 10 stamps");
        assertThat(Texts.t("tr", "stamp.progress", 3, 10)).isNotEqualTo("3 von 10 Stempeln").contains("3").contains("10");
        assertThat(Texts.t("ar", "stamp.progress", 3, 10)).contains("3").contains("10");
        // Unbekannte Sprache und Gross-/Kleinschreibung
        assertThat(Texts.t("xx", "stamp.progress", 3, 10)).isEqualTo("3 von 10 Stempeln");
        assertThat(Texts.t("EN", "member.visits")).isEqualTo("Visits");
        assertThat(Texts.t(null, "member.visits")).isEqualTo("Besuche");
        // Unbekannter Schluessel: der Schluessel selbst, kein Absturz
        assertThat(Texts.t("de", "gibt.es.nicht")).isEqualTo("gibt.es.nicht");
    }

    @Test
    void sprache_kunde_sonstDeutsch_ladenZaehltNicht() {
        Shop laden = mock(Shop.class);
        when(laden.getLanguageOrDefault()).thenReturn("tr");
        Customer kunde = mock(Customer.class);
        when(kunde.getLanguage()).thenReturn("en");

        assertThat(Texts.sprache(kunde, laden)).isEqualTo("en");
        assertThat(Texts.sprache(null, laden)).isEqualTo("de");
        assertThat(Texts.sprache(laden)).isEqualTo("de");
        assertThat(Texts.sprache(null, null)).isEqualTo("de");
        assertThat(Texts.gewaehlt("TR")).isEqualTo("tr");
        assertThat(Texts.gewaehlt("xx")).isNull();
        assertThat(Texts.gewaehlt(null)).isNull();
        // Mock ohne Stubbing (wie in vielen Tests): null = Deutsch
        assertThat(Texts.sprache(mock(Customer.class), mock(Shop.class))).isEqualTo("de");
    }

    @Test
    void htmlAttrs_rtlNurArabisch() {
        assertThat(Texts.htmlAttrs("ar")).isEqualTo(" lang=\"ar\" dir=\"rtl\"");
        assertThat(Texts.htmlAttrs("tr")).isEqualTo(" lang=\"tr\" dir=\"ltr\"");
        assertThat(Texts.htmlAttrs(null)).isEqualTo(" lang=\"de\" dir=\"ltr\"");
        assertThat(Texts.isRtl("ar")).isTrue();
        assertThat(Texts.isRtl("en")).isFalse();
    }
}
