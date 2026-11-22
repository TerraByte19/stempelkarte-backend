package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilialZugangTest {

    private final Shop shop = Shop.create("z@kette.de", "HASH", "Kette", 3);

    @Test
    void benutzername_wirdKleinUndGetrimmt() {
        assertThat(FilialZugang.benutzernameSauber("  BackBaron-Mitte ")).isEqualTo("backbaron-mitte");
    }

    @Test
    void benutzername_ohneAtUndSonderzeichen() {
        for (String falsch : new String[]{null, "", "ab", "a@b.de", "mit leer", "ümlaut", "-vorne", "x".repeat(41)}) {
            assertThatThrownBy(() -> FilialZugang.benutzernameSauber(falsch))
                    .as(String.valueOf(falsch))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void neuerZugang_istOffenMitVersionNull() {
        FilialZugang z = FilialZugang.create(shop, "backbaron-mitte", "HASH", null);
        assertThat(z.getId()).startsWith("FZ-");
        assertThat(z.isGesperrt()).isFalse();
        assertThat(z.getTokenVersion()).isZero();
        assertThat(z.getOrtId()).isNull();
    }

    @Test
    void passwortUndSperre_zaehlenDieVersionHoch() {
        FilialZugang z = FilialZugang.create(shop, "backbaron-mitte", "HASH", "ORT-1");
        z.neuesPasswort("NEU");
        assertThat(z.getTokenVersion()).isEqualTo(1);
        assertThat(z.getPasswortHash()).isEqualTo("NEU");
        z.sperre(true);
        assertThat(z.getTokenVersion()).isEqualTo(2);
        assertThat(z.isGesperrt()).isTrue();
        z.sperre(false);
        assertThat(z.getTokenVersion()).isEqualTo(3);
        assertThat(z.isGesperrt()).isFalse();
    }

    @Test
    void ort_ohneEintragGiltAlsAktiv() {
        ShopOrt ort = ShopOrt.create(shop, "Nord", 52.5, 13.4);
        assertThat(ort.isAktiv()).isTrue();
        ort.setAktiv(false);
        assertThat(ort.isAktiv()).isFalse();
    }
}
