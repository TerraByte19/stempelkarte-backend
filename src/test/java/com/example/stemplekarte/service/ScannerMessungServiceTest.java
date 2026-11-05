package com.example.stemplekarte.service;

import com.example.stemplekarte.model.ScannerMessung;
import com.example.stemplekarte.repository.ScannerMessungRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Scanner-Messung: Geraetetyp aus dem User-Agent, Zusammenfassung, Plausibilitaet. */
class ScannerMessungServiceTest {

    private static final Instant JETZT = Instant.parse("2026-11-05T10:00:00Z");
    private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 Safari/604.1";
    private static final String ANDROID = "Mozilla/5.0 (Linux; Android 14; SM-G991B Build/UP1A) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36";

    private static ScannerMessung m(String ua, String gegner, int alt, Integer gegnerMs, String aktiv) {
        return ScannerMessung.create("SHOP-1", "Kasse", null, ua, gegner, alt, gegnerMs, aktiv, JETZT);
    }

    @Test
    void geraetetyp_ausUserAgent() {
        assertThat(m(IPHONE, "neu", 1, 1, null).geraeteTyp()).isEqualTo("iPhone iOS 17");
        assertThat(m(ANDROID, "neu", 1, 1, null).geraeteTyp()).isEqualTo("Android SM-G991B");
        assertThat(ScannerMessung.create("S", null, "andere", "Mozilla/5.0 (Windows NT 10.0)", "neu", 1, 1, null, JETZT).geraeteTyp())
                .isEqualTo("andere");
    }

    @Test
    void zusammenfassung_jeGeraetUndGegner() {
        List<ScannerMessung> l = List.of(
                m(IPHONE, "neu", 900, 300, null), m(IPHONE, "neu", 800, 300, null), m(IPHONE, "neu", 700, null, null),
                m(IPHONE, "neu", 600, 650, "neu"),
                m(ANDROID, "handy", 500, 100, null));
        var zeilen = ScannerMessungService.zusammenfassung(l);
        assertThat(zeilen).hasSize(2);
        var iphone = zeilen.get(0);
        assertThat(iphone.geraet()).isEqualTo("iPhone iOS 17");
        assertThat(iphone.gegner()).isEqualTo("neu");
        assertThat(iphone.runden()).isEqualTo(4);
        assertThat(iphone.zuerst()).isEqualTo(2);
        assertThat(iphone.anteilProzent()).isEqualTo(50);
        assertThat(iphone.verfehlt()).isEqualTo(1);
        assertThat(iphone.gewechselt()).isEqualTo(1);
        assertThat(iphone.vorsprungMs()).isEqualTo(500);   // Median von 600, 500, -50
        assertThat(zeilen.get(1).geraet()).isEqualTo("Android SM-G991B");
        assertThat(zeilen.get(1).vorsprungMs()).isEqualTo(400);
    }

    @Test
    void annehmen_prueftPlausibilitaetUndLimit() {
        ScannerMessungRepository repo = mock(ScannerMessungRepository.class);
        when(repo.save(any(ScannerMessung.class))).thenAnswer(i -> i.getArgument(0));
        ScannerMessungService s = new ScannerMessungService(repo);
        assertThat(s.annehmen("SHOP-1", "Kasse", "ios", IPHONE, "neu", 900, 300, null, JETZT)).isNotNull();
        assertThat(s.annehmen("SHOP-1", "Kasse", "ios", IPHONE, "fremd", 900, 300, null, JETZT)).isNull();
        assertThat(s.annehmen("SHOP-1", "Kasse", "ios", IPHONE, "neu", -1, 300, null, JETZT)).isNull();
        assertThat(s.annehmen("SHOP-1", "Kasse", "ios", IPHONE, "neu", 900, 99_999, null, JETZT)).isNull();
        assertThat(s.annehmen("SHOP-1", "Kasse", "ios", IPHONE, "neu", null, 300, null, JETZT)).isNull();
        when(repo.countByShopIdAndErstelltAmAfter(anyString(), any())).thenReturn((long) ScannerMessungService.MAX_JE_LADEN_UND_TAG);
        assertThat(s.annehmen("SHOP-1", "Kasse", "ios", IPHONE, "neu", 900, 300, null, JETZT)).isNull();
    }
}
