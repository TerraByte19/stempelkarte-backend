package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tagesangebot: gilt bis Tagesende Berlin, geht als Kampagne "Heute: ..."
 * raus, bleibt auch gespeichert, wenn der Push ausfaellt.
 */
class TagesangebotServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private Shop shop;
    private KampagnenService kampagnen;
    private TagesangebotService dienst;
    private final Instant um9 = LocalDate.of(2026, 11, 3).atTime(9, 0).atZone(ZONE).toInstant();

    @BeforeEach
    void setUp() {
        shop = Shop.create("a@b.de", "hash", "Café Mokka", 3);
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.findById(shop.getId())).thenReturn(Optional.of(shop));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        kampagnen = mock(KampagnenService.class);
        dienst = new TagesangebotService(repo, kampagnen);
    }

    @Test
    void setzen_speichertBisTagesende_undPusht() {
        Kampagne k = Kampagne.starte(shop, "Heute: Cappuccino 2,50 €", Zielgruppe.TAGESANGEBOT, 12, um9);
        when(kampagnen.starte(eq(shop), eq("Heute: Cappuccino 2,50 €"), eq("TAGESANGEBOT"))).thenReturn(k);

        TagesangebotService.Stand s = dienst.setzen(shop.getId(), "  Cappuccino 2,50 €\n", um9);

        assertThat(s.text()).isEqualTo("Cappuccino 2,50 €");
        assertThat(s.aktiv()).isTrue();
        assertThat(s.empfaenger()).isEqualTo(12);
        Instant mitternacht = LocalDate.of(2026, 11, 4).atStartOfDay(ZONE).toInstant();
        assertThat(shop.getTagesangebotBis()).isEqualTo(mitternacht);
        assertThat(shop.hatTagesangebotFeld()).isTrue();
        // Gilt bis kurz vor Mitternacht, danach nicht mehr.
        assertThat(shop.tagesangebot(mitternacht.minusSeconds(1))).isEqualTo("Cappuccino 2,50 €");
        assertThat(shop.tagesangebot(mitternacht)).isNull();
    }

    @Test
    void setzen_ohnePush_bleibtGespeichert() {
        when(kampagnen.starte(any(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("In dieser Gruppe ist gerade niemand"));
        TagesangebotService.Stand s = dienst.setzen(shop.getId(), "Kuchen 1 €", um9);
        assertThat(s.aktiv()).isTrue();
        assertThat(s.empfaenger()).isZero();
        assertThat(shop.getTagesangebotText()).isEqualTo("Kuchen 1 €");
    }

    @Test
    void setzen_leerOderZuLang_abgelehnt() {
        assertThatThrownBy(() -> dienst.setzen(shop.getId(), "  ", um9))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.setzen(shop.getId(), "x".repeat(81), um9))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("80");
        verify(kampagnen, never()).starte(any(), anyString(), anyString());
        assertThat(shop.hatTagesangebotFeld()).isFalse();
    }

    @Test
    void loeschen_textWeg_feldBleibt() {
        when(kampagnen.starte(any(), anyString(), anyString()))
                .thenReturn(Kampagne.starte(shop, "Heute: x", Zielgruppe.TAGESANGEBOT, 1, um9));
        dienst.setzen(shop.getId(), "Kuchen 1 €", um9);
        TagesangebotService.Stand s = dienst.loeschen(shop.getId());
        assertThat(s.aktiv()).isFalse();
        assertThat(s.text()).isNull();
        assertThat(shop.hatTagesangebotFeld()).isTrue();
    }

    @Test
    void stand_abgelaufen_textBleibtAlsVorschlag() {
        shop.setzeTagesangebot("Kuchen 1 €", um9, um9.plusSeconds(10));
        TagesangebotService.Stand s = dienst.stand(shop);
        assertThat(s.aktiv()).isFalse();
        assertThat(s.text()).isEqualTo("Kuchen 1 €");
    }

    @Test
    void zielgruppe_trifftAlle_nichtWaehlbar() {
        assertThat(Zielgruppe.TAGESANGEBOT.waehlbar()).isFalse();
        assertThat(Zielgruppe.waehlbare()).doesNotContain(Zielgruppe.TAGESANGEBOT);
    }
}
