package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Geburtstagsgruss: ab 9 Uhr, einmal pro Laden und Tag, nur wenn an.
 * Die Zielgruppe trifft den Tag, nicht nur den Monat.
 */
class GeburtstagsgrussTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private Shop shop;
    private ShopRepository shopRepo;
    private KampagneRepository kampagneRepo;
    private KampagnenService kampagnen;
    private GeburtstagsService dienst;
    private final Instant um10 = LocalDate.of(2026, 11, 2).atTime(10, 0).atZone(ZONE).toInstant();

    @BeforeEach
    void setUp() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.isActive()).thenReturn(true);
        when(shop.isGeburtstagsgrussAktiv()).thenReturn(true);
        when(shop.geburtstagsText()).thenReturn("Alles Gute!");
        shopRepo = mock(ShopRepository.class);
        when(shopRepo.findAll()).thenReturn(List.of(shop));
        kampagneRepo = mock(KampagneRepository.class);
        kampagnen = mock(KampagnenService.class);
        when(kampagnen.starte(eq(shop), anyString(), eq("GEBURTSTAG_HEUTE")))
                .thenReturn(Kampagne.starte(shop, "Alles Gute!", Zielgruppe.GEBURTSTAG_HEUTE, 2, um10));
        dienst = new GeburtstagsService(shopRepo, kampagneRepo, kampagnen);
    }

    @Test
    void vorNeunUhr_nichts() {
        Instant um8 = LocalDate.of(2026, 11, 2).atTime(8, 59).atZone(ZONE).toInstant();
        assertThat(dienst.lauf(um8)).isZero();
        verify(kampagnen, never()).starte(any(), anyString(), anyString());
    }

    @Test
    void abNeunUhr_einmalProTag() {
        assertThat(dienst.lauf(um10)).isEqualTo(1);
        assertThat(dienst.lauf(um10.plusSeconds(900))).isZero();
        verify(kampagnen, times(1)).starte(eq(shop), eq("Alles Gute!"), eq("GEBURTSTAG_HEUTE"));
        // Naechster Tag wieder.
        assertThat(dienst.lauf(um10.plusSeconds(86_400))).isEqualTo(1);
    }

    @Test
    void heuteSchonInDerDatenbank_keinZweiter() {
        when(kampagneRepo.existsByShopAndGruppeAndErstelltAmGreaterThanEqual(eq(shop), eq("GEBURTSTAG_HEUTE"), any()))
                .thenReturn(true);
        assertThat(dienst.lauf(um10)).isZero();
        verify(kampagnen, never()).starte(any(), anyString(), anyString());
    }

    @Test
    void ausOderInaktiv_nichts() {
        when(shop.isGeburtstagsgrussAktiv()).thenReturn(false);
        assertThat(dienst.lauf(um10)).isZero();
        when(shop.isGeburtstagsgrussAktiv()).thenReturn(true);
        when(shop.isActive()).thenReturn(false);
        assertThat(dienst.lauf(um10)).isZero();
        verify(kampagnen, never()).starte(any(), anyString(), anyString());
    }

    @Test
    void niemandHatGeburtstag_morgenWieder() {
        when(kampagnen.starte(eq(shop), anyString(), eq("GEBURTSTAG_HEUTE")))
                .thenThrow(new IllegalArgumentException("In dieser Gruppe ist gerade niemand"));
        assertThat(dienst.lauf(um10)).isZero();
        // Heute nicht nochmal probieren, morgen schon.
        dienst.lauf(um10.plusSeconds(900));
        verify(kampagnen, times(1)).starte(any(), anyString(), anyString());
        dienst.lauf(um10.plusSeconds(86_400));
        verify(kampagnen, times(2)).starte(any(), anyString(), anyString());
    }

    @Test
    void zielgruppe_trifftDenTag() {
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        LocalDate heute = um10.atZone(ZONE).toLocalDate();
        CustomerCard heuteGeb = CustomerCard.create(mock(Customer.class), card);
        heuteGeb.anmeldedatenErgaenzen(heute.minusYears(25), null);
        CustomerCard morgenGeb = CustomerCard.create(mock(Customer.class), card);
        morgenGeb.anmeldedatenErgaenzen(heute.minusYears(25).plusDays(1), null);
        CustomerCard ohne = CustomerCard.create(mock(Customer.class), card);
        assertThat(Zielgruppe.GEBURTSTAG_HEUTE.passt(heuteGeb, um10, c -> List.of())).isTrue();
        assertThat(Zielgruppe.GEBURTSTAG_HEUTE.passt(morgenGeb, um10, c -> List.of())).isFalse();
        assertThat(Zielgruppe.GEBURTSTAG_HEUTE.passt(ohne, um10, c -> List.of())).isFalse();
        assertThat(Zielgruppe.GEBURTSTAG_HEUTE.waehlbar()).isFalse();
        assertThat(Zielgruppe.waehlbare()).doesNotContain(Zielgruppe.GEBURTSTAG_HEUTE);
    }

    @Test
    void zielgruppe_29Februar_inNichtSchaltjahrAm28() {
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        CustomerCard cc = CustomerCard.create(mock(Customer.class), card);
        cc.anmeldedatenErgaenzen(LocalDate.of(2000, 2, 29), null);
        Instant am28 = LocalDate.of(2027, 2, 28).atTime(10, 0).atZone(ZONE).toInstant();
        Instant am29 = LocalDate.of(2028, 2, 29).atTime(10, 0).atZone(ZONE).toInstant();
        assertThat(Zielgruppe.GEBURTSTAG_HEUTE.passt(cc, am28, c -> List.of())).isTrue();
        assertThat(Zielgruppe.GEBURTSTAG_HEUTE.passt(cc, am29, c -> List.of())).isTrue();
    }
}
