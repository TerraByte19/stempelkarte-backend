package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Testphase: angefangene Tage zaehlen, vorbei = 0, keine = null. */
class TestphaseTest {

    private final Instant jetzt = Instant.parse("2026-11-04T12:00:00Z");

    @Test
    void ohneTestphase_null() {
        Shop s = Shop.create("a@b.de", "hash", "Laden", 3);
        assertThat(s.getTestBis()).isNull();
        assertThat(s.testTageUebrig(jetzt)).isNull();
    }

    @Test
    void tageZaehlen_angefangeneAufgerundet() {
        Shop s = Shop.create("a@b.de", "hash", "Laden", 3);
        s.setTestBis(jetzt.plus(Duration.ofDays(Shop.TEST_TAGE)));
        assertThat(s.testTageUebrig(jetzt)).isEqualTo(14);
        assertThat(s.testTageUebrig(jetzt.plusSeconds(1))).isEqualTo(14);
        assertThat(s.testTageUebrig(jetzt.plus(Duration.ofDays(13)).plusSeconds(1))).isEqualTo(1);
        assertThat(s.testTageUebrig(jetzt.plus(Duration.ofDays(14)))).isZero();
        assertThat(s.testTageUebrig(jetzt.plus(Duration.ofDays(30)))).isZero();
    }
}
