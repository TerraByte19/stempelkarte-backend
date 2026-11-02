package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Was im Nachrichtenfeld des Passes steht, wenn Rueckhol-Nachricht und
 * Kampagne sich ueberlagern - und wann iOS eine Mitteilung zeigen darf.
 *
 * Die Falle: das changeMessage haengt am Feld. Steht es dort auch nach dem
 * naechsten Besuch noch, meldet iOS beim Stempeln die alte Kampagne ein
 * zweites Mal. Deshalb gilt eine Nachricht nur als "frisch", solange der
 * Kunde seit ihr nicht da war.
 */
class KampagnenKarteTest {

    private final Instant jetzt = Instant.now();

    private CustomerCard karte() {
        Shop shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Café Mokka");
        Card card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        CustomerCard cc = CustomerCard.create(mock(Customer.class), card);
        cc.addStamp();
        return cc;
    }

    @Test
    void ohneNachricht_nichts() {
        CustomerCard cc = karte();
        assertThat(cc.nachrichtText()).isNull();
        assertThat(cc.nachrichtFrisch()).isFalse();
    }

    @Test
    void kampagne_stehtImFeld_undIstFrisch() {
        CustomerCard cc = karte();
        cc.kampagneErhalten("Heute 20% auf Kuchen", jetzt.plusSeconds(10));
        assertThat(cc.nachrichtText()).isEqualTo("Heute 20% auf Kuchen");
        assertThat(cc.nachrichtFrisch()).isTrue();
        assertThat(cc.getKampagneAm()).isEqualTo(jetzt.plusSeconds(10));
    }

    @Test
    void nachDemBesuch_textBleibt_aberNichtMehrFrisch() throws Exception {
        CustomerCard cc = karte();
        cc.kampagneErhalten("Heute 20% auf Kuchen", Instant.now());
        Thread.sleep(5);
        cc.addStamp();
        assertThat(cc.nachrichtText()).isEqualTo("Heute 20% auf Kuchen");
        assertThat(cc.nachrichtFrisch()).isFalse();
    }

    @Test
    void rueckholNachKampagne_rueckholGewinnt() {
        CustomerCard cc = karte();
        cc.kampagneErhalten("Kampagne", jetzt.plusSeconds(10));
        cc.rueckholGesendet("Wir vermissen dich!", jetzt.plusSeconds(20));
        assertThat(cc.nachrichtText()).isEqualTo("Wir vermissen dich!");
        assertThat(cc.nachrichtFrisch()).isTrue();
    }

    @Test
    void kampagneNachRueckhol_kampagneGewinnt() {
        CustomerCard cc = karte();
        cc.rueckholGesendet("Wir vermissen dich!", jetzt.plusSeconds(10));
        cc.kampagneErhalten("Kampagne", jetzt.plusSeconds(20));
        assertThat(cc.nachrichtText()).isEqualTo("Kampagne");
        assertThat(cc.nachrichtFrisch()).isTrue();
    }

    @Test
    void rueckholNachBesuchWeg_kampagneBleibtStill() throws Exception {
        CustomerCard cc = karte();
        cc.kampagneErhalten("Kampagne", Instant.now());
        Thread.sleep(5);
        cc.rueckholGesendet("Wir vermissen dich!", Instant.now());
        Thread.sleep(5);
        cc.addStamp();
        // Rueckhol ist mit dem Besuch erledigt, das Feld faellt auf die
        // Kampagne zurueck - ohne Mitteilung, die kennt der Kunde schon.
        assertThat(cc.nachrichtText()).isEqualTo("Kampagne");
        assertThat(cc.nachrichtFrisch()).isFalse();
    }

    @Test
    void verschickenZaehltNichtAlsBesuch_bewegtAberUpdatedAt() {
        CustomerCard cc = karte();
        Instant besuch = cc.letzterBesuch();
        Instant spaeter = jetzt.plus(Duration.ofDays(3));
        cc.kampagneErhalten("x", spaeter);
        assertThat(cc.letzterBesuch()).isEqualTo(besuch);
        assertThat(cc.getUpdatedAt()).isEqualTo(spaeter);
    }

    @Test
    void zuLangerText_wirdGekuerzt() {
        CustomerCard cc = karte();
        cc.kampagneErhalten("x".repeat(500), jetzt);
        assertThat(cc.getKampagneText()).hasSize(300);
    }

    // ── Shop: seit wann Paesse das Nachrichtenfeld tragen ────────────────

    @Test
    void shop_ohneAlles_keinFeld() {
        Shop shop = Shop.create("a@b.de", "hash", "Laden", 3);
        assertThat(shop.hatNachrichtenFeld()).isFalse();
        assertThat(shop.nachrichtenFeldSeit()).isNull();
    }

    @Test
    void shop_kampagneSchaltetFeldDauerhaftEin() {
        Shop shop = Shop.create("a@b.de", "hash", "Laden", 3);
        shop.nachrichtenEinschalten(jetzt);
        shop.nachrichtenEinschalten(jetzt.plusSeconds(60));
        assertThat(shop.hatNachrichtenFeld()).isTrue();
        assertThat(shop.getNachrichtenSeit()).isEqualTo(jetzt);
        assertThat(shop.nachrichtenFeldSeit()).isEqualTo(jetzt);
    }

    @Test
    void shop_rueckholEinschalten_setztAuchNachrichtenSeit() {
        Shop shop = Shop.create("a@b.de", "hash", "Laden", 3);
        shop.updateRueckhol(true, 14, null);
        assertThat(shop.getNachrichtenSeit()).isNotNull();
        assertThat(shop.nachrichtenFeldSeit()).isEqualTo(shop.getWinbackEnabledAt());
        // Rueckhol wieder aus: das Feld bleibt, Paesse verlieren es nicht.
        shop.updateRueckhol(false, 14, null);
        assertThat(shop.hatNachrichtenFeld()).isTrue();
    }

    @Test
    void shop_bestandslaedenMitRueckhol_feldSeitEinschalten() {
        // Vor dieser Spalte: winbackEnabledAt gesetzt, nachrichtenSeit null.
        Shop shop = mock(Shop.class);
        when(shop.isRueckholAktiv()).thenReturn(true);
        when(shop.getWinbackEnabledAt()).thenReturn(jetzt);
        when(shop.getNachrichtenSeit()).thenReturn(null);
        when(shop.hatNachrichtenFeld()).thenCallRealMethod();
        when(shop.nachrichtenFeldSeit()).thenCallRealMethod();
        assertThat(shop.hatNachrichtenFeld()).isTrue();
        assertThat(shop.nachrichtenFeldSeit()).isEqualTo(jetzt);
    }

    @Test
    void shop_feldSeit_istDasFruehereDatum() {
        Shop shop = mock(Shop.class);
        when(shop.isRueckholAktiv()).thenReturn(true);
        when(shop.getWinbackEnabledAt()).thenReturn(jetzt.plusSeconds(100));
        when(shop.getNachrichtenSeit()).thenReturn(jetzt);
        when(shop.nachrichtenFeldSeit()).thenCallRealMethod();
        assertThat(shop.nachrichtenFeldSeit()).isEqualTo(jetzt);
    }
}
