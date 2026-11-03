package com.example.stemplekarte.service;

import com.example.stemplekarte.model.AutoNachricht;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.KampagneEmpfaenger;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AutoNachrichtRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.KampagneEmpfaengerRepository;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Automaten: ab 10 Uhr, je Laden und Typ einmal am Tag (VIP: im Monat),
 * eigener oder Standardtext, Merker auf die Empfaenger (Willkommen auf alle
 * Karten der Person, Runden-Merker nur auf die Karte).
 */
class AutoNachrichtenServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private Shop shop;
    private AutoNachrichtRepository autoRepo;
    private ShopRepository shopRepo;
    private KampagneRepository kampagneRepo;
    private KampagneEmpfaengerRepository empfaengerRepo;
    private CustomerCardRepository ccRepo;
    private KampagnenService kampagnen;
    private AutoNachrichtenService dienst;
    private final Instant um11 = LocalDate.of(2026, 11, 3).atTime(11, 0).atZone(ZONE).toInstant();

    @BeforeEach
    void setUp() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.isActive()).thenReturn(true);
        autoRepo = mock(AutoNachrichtRepository.class);
        when(autoRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        shopRepo = mock(ShopRepository.class);
        when(shopRepo.findById("SHOP-1")).thenReturn(Optional.of(shop));
        kampagneRepo = mock(KampagneRepository.class);
        empfaengerRepo = mock(KampagneEmpfaengerRepository.class);
        ccRepo = mock(CustomerCardRepository.class);
        kampagnen = mock(KampagnenService.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> c = inv.getArgument(0);
            c.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        dienst = new AutoNachrichtenService(autoRepo, shopRepo, kampagneRepo, empfaengerRepo, ccRepo, kampagnen, tx);
    }

    private AutoNachricht aktiv(AutoNachrichtTyp typ, String text) {
        AutoNachricht a = AutoNachricht.neu(shop, typ.name(), um11);
        a.einstellen(true, text, um11);
        return a;
    }

    private Kampagne kampagne(AutoNachrichtTyp typ) {
        return Kampagne.starte(shop, typ.standardText(), typ.gruppe(), 1, um11);
    }

    private void empfaenger(Kampagne k, CustomerCard cc) {
        KampagneEmpfaenger zeile = mock(KampagneEmpfaenger.class);
        when(zeile.getCustomerCardId()).thenReturn(cc.getId());
        when(empfaengerRepo.findByKampagneId(k.getId())).thenReturn(List.of(zeile));
        when(ccRepo.findAllById(List.of(cc.getId()))).thenReturn(List.of(cc));
    }

    // ── Laeufer ─────────────────────────────────────────────────────────

    @Test
    void vorZehnUhr_nichts() {
        when(autoRepo.findAktive()).thenReturn(List.of(aktiv(AutoNachrichtTyp.WILLKOMMEN, null)));
        Instant um9 = LocalDate.of(2026, 11, 3).atTime(9, 59).atZone(ZONE).toInstant();
        assertThat(dienst.lauf(um9)).isZero();
        verify(kampagnen, never()).starte(any(), anyString(), anyString());
    }

    @Test
    void inaktiverLaden_nichts() {
        when(shop.isActive()).thenReturn(false);
        when(autoRepo.findAktive()).thenReturn(List.of(aktiv(AutoNachrichtTyp.WILLKOMMEN, null)));
        assertThat(dienst.lauf(um11)).isZero();
        verify(kampagnen, never()).starte(any(), anyString(), anyString());
    }

    @Test
    void willkommen_standardtext_einmalProTag_markiertAlleKartenDerPerson() {
        when(autoRepo.findAktive()).thenReturn(List.of(aktiv(AutoNachrichtTyp.WILLKOMMEN, null)));
        Kampagne k = kampagne(AutoNachrichtTyp.WILLKOMMEN);
        when(kampagnen.starte(eq(shop), eq(AutoNachrichtTyp.WILLKOMMEN.standardText()), eq("AUTO_WILLKOMMEN")))
                .thenReturn(k);

        Customer kunde = mock(Customer.class);
        Card stempel = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        Card punkte = Card.create(shop, "Punkte", "x", 10, "x");
        Shop fremd = mock(Shop.class);
        when(fremd.getId()).thenReturn("SHOP-2");
        Card fremdeKarte = Card.create(fremd, "Anderswo", "x", 10, "x");
        CustomerCard empf = CustomerCard.create(kunde, stempel);
        CustomerCard zweite = CustomerCard.create(kunde, punkte);
        CustomerCard woanders = CustomerCard.create(kunde, fremdeKarte);
        empfaenger(k, empf);
        when(ccRepo.findByCustomer(kunde)).thenReturn(List.of(empf, zweite, woanders));

        assertThat(dienst.lauf(um11)).isEqualTo(1);
        assertThat(empf.getAutoWillkommenAm()).isEqualTo(um11);
        assertThat(zweite.getAutoWillkommenAm()).isEqualTo(um11);
        assertThat(woanders.getAutoWillkommenAm()).isNull();
        verify(ccRepo, never()).save(woanders);

        // Gleicher Tag: nichts. Naechster Tag: wieder.
        assertThat(dienst.lauf(um11.plusSeconds(900))).isZero();
        assertThat(dienst.lauf(um11.plusSeconds(86_400))).isEqualTo(1);
        verify(kampagnen, times(2)).starte(any(), anyString(), anyString());
    }

    @Test
    void eigenerText_wirdGenommen() {
        when(autoRepo.findAktive()).thenReturn(List.of(aktiv(AutoNachrichtTyp.ZWEITER_BESUCH, "Schön, dass du wieder da bist!")));
        Kampagne k = kampagne(AutoNachrichtTyp.ZWEITER_BESUCH);
        when(kampagnen.starte(eq(shop), eq("Schön, dass du wieder da bist!"), eq("AUTO_ZWEITER_BESUCH"))).thenReturn(k);
        assertThat(dienst.lauf(um11)).isEqualTo(1);
    }

    @Test
    void fastVoll_rundenMerkerNurAufDerKarte() {
        when(autoRepo.findAktive()).thenReturn(List.of(aktiv(AutoNachrichtTyp.FAST_VOLL, null)));
        Kampagne k = kampagne(AutoNachrichtTyp.FAST_VOLL);
        when(kampagnen.starte(eq(shop), anyString(), eq("AUTO_FAST_VOLL"))).thenReturn(k);

        Customer kunde = mock(Customer.class);
        Card stempel = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        CustomerCard empf = CustomerCard.create(kunde, stempel);
        for (int i = 0; i < 8; i++) empf.addStamp();
        empfaenger(k, empf);

        assertThat(dienst.lauf(um11)).isEqualTo(1);
        assertThat(empf.getAutoFastVollBei()).isZero();
        // Andere Karten der Person werden nicht angefasst.
        verify(ccRepo, never()).findByCustomer(any());
        // Naechste Runde: Praemie geholt, Merker passt nicht mehr.
        empf.addStamp(); empf.addStamp();
        empf.redeemReward(10);
        assertThat(AutoNachrichtTyp.FAST_VOLL.markiert(empf)).isFalse();
    }

    @Test
    void vip_einmalImMonat_ohneMerker() {
        when(autoRepo.findAktive()).thenReturn(List.of(aktiv(AutoNachrichtTyp.VIP, null)));
        Kampagne k = kampagne(AutoNachrichtTyp.VIP);
        when(kampagnen.starte(eq(shop), anyString(), eq("AUTO_VIP"))).thenReturn(k);

        assertThat(dienst.lauf(um11)).isEqualTo(1);
        // Naechster Tag im selben Monat: nichts. Naechster Monat: wieder.
        assertThat(dienst.lauf(um11.plusSeconds(86_400))).isZero();
        Instant dezember = LocalDate.of(2026, 12, 1).atTime(11, 0).atZone(ZONE).toInstant();
        assertThat(dienst.lauf(dezember)).isEqualTo(1);
        verify(empfaengerRepo, never()).findByKampagneId(anyString());
    }

    @Test
    void schonInDerDatenbank_keinZweiter() {
        when(autoRepo.findAktive()).thenReturn(List.of(aktiv(AutoNachrichtTyp.PRAEMIE_BEREIT, null)));
        when(kampagneRepo.existsByShopAndGruppeAndErstelltAmGreaterThanEqual(eq(shop), eq("AUTO_PRAEMIE"), any()))
                .thenReturn(true);
        assertThat(dienst.lauf(um11)).isZero();
        verify(kampagnen, never()).starte(any(), anyString(), anyString());
    }

    @Test
    void niemandFaellig_morgenWieder() {
        when(autoRepo.findAktive()).thenReturn(List.of(aktiv(AutoNachrichtTyp.WILLKOMMEN, null)));
        when(kampagnen.starte(eq(shop), anyString(), eq("AUTO_WILLKOMMEN")))
                .thenThrow(new IllegalArgumentException("In dieser Gruppe ist gerade niemand"));
        assertThat(dienst.lauf(um11)).isZero();
        assertThat(dienst.lauf(um11.plusSeconds(86_400))).isZero();
        verify(kampagnen, times(2)).starte(eq(shop), anyString(), eq("AUTO_WILLKOMMEN"));
    }

    @Test
    void kaputterLaden_haeltAndereNichtAuf() {
        Shop zwei = mock(Shop.class);
        when(zwei.getId()).thenReturn("SHOP-2");
        when(zwei.isActive()).thenReturn(true);
        AutoNachricht b = AutoNachricht.neu(zwei, "WILLKOMMEN", um11);
        b.einstellen(true, null, um11);
        when(autoRepo.findAktive()).thenReturn(List.of(aktiv(AutoNachrichtTyp.WILLKOMMEN, null), b));
        when(kampagnen.starte(eq(shop), anyString(), anyString())).thenThrow(new RuntimeException("DB weg"));
        when(kampagnen.starte(eq(zwei), anyString(), anyString())).thenReturn(kampagne(AutoNachrichtTyp.WILLKOMMEN));
        assertThat(dienst.lauf(um11)).isEqualTo(1);
    }

    // ── Einstellungen ───────────────────────────────────────────────────

    @Test
    void liste_fuenfEintraege_ausOhneZeile() {
        AutoNachricht an = AutoNachricht.neu(shop, "VIP", um11);
        an.einstellen(true, "Danke, {name}!", um11);
        when(autoRepo.findByShop(shop)).thenReturn(List.of(an));
        List<AutoNachrichtenService.Eintrag> l = dienst.liste(shop);
        assertThat(l).hasSize(7);
        assertThat(l).extracting(AutoNachrichtenService.Eintrag::typ)
                .containsExactly("WILLKOMMEN", "ZWEITER_BESUCH", "FAST_VOLL", "PRAEMIE_BEREIT", "VIP", "STUFE", "PAKET_LEER");
        assertThat(l.get(0).aktiv()).isFalse();
        assertThat(l.get(0).text()).isNull();
        assertThat(l.get(0).standard()).isEqualTo(AutoNachrichtTyp.WILLKOMMEN.standardText());
        assertThat(l.get(4).aktiv()).isTrue();
        assertThat(l.get(4).text()).isEqualTo("Danke, {name}!");
    }

    @Test
    void einstellen_legtAn_leerIstStandard_zuLangKnallt() {
        when(autoRepo.findByShopAndTyp(shop, "WILLKOMMEN")).thenReturn(Optional.empty());
        AutoNachrichtenService.Eintrag e = dienst.einstellen("SHOP-1", AutoNachrichtTyp.WILLKOMMEN, true, "  ");
        assertThat(e.aktiv()).isTrue();
        assertThat(e.text()).isNull();
        verify(autoRepo).save(any());

        assertThatThrownBy(() -> dienst.einstellen("SHOP-1", AutoNachrichtTyp.WILLKOMMEN, true, "x".repeat(161)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("160");
        assertThatThrownBy(() -> AutoNachrichtTyp.von("newsletter"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AutoNachrichtTyp.von("fast_voll")).isEqualTo(AutoNachrichtTyp.FAST_VOLL);
    }
}
