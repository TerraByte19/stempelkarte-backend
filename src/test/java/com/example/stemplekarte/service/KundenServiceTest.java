package com.example.stemplekarte.service;

import com.example.stemplekarte.model.AppleDeviceRegistration;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stempel-Korrektur: Bereich 0..Schwelle, nur Stempelkarten, nicht bei
 * offenem Geschenk, nicht fuer fremde Laeden. Und: kein Besuch - der
 * letzte Besuch bleibt, wie er war.
 */
class KundenServiceTest {

    private Shop shop;
    private Card card;
    private CustomerCardRepository ccRepo;
    private AppleDeviceRepository deviceRepo;
    private KundenService dienst;

    @BeforeEach
    void setUp() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        ccRepo = mock(CustomerCardRepository.class);
        when(ccRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        deviceRepo = mock(AppleDeviceRepository.class);
        dienst = new KundenService(ccRepo, deviceRepo);
    }

    private CustomerCard kundenkarte(Card karte, int stempel) {
        Customer c = mock(Customer.class);
        when(c.getId()).thenReturn("CUST-1");
        when(c.getName()).thenReturn("Adham");
        when(c.getEmail()).thenReturn("adham@test.de");
        when(c.isEmailConfirmed()).thenReturn(true);
        CustomerCard cc = CustomerCard.create(c, karte);
        for (int i = 0; i < stempel; i++) cc.addStamp();
        when(ccRepo.findById(cc.getId())).thenReturn(Optional.of(cc));
        return cc;
    }

    @Test
    void muster_kleinUndMitProzent() {
        assertThat(KundenService.muster(null)).isEqualTo("%");
        assertThat(KundenService.muster("  ")).isEqualTo("%");
        assertThat(KundenService.muster(" Adham ")).isEqualTo("%adham%");
    }

    @Test
    void korrektur_setztStand_laesstBesuchStehen() throws Exception {
        CustomerCard cc = kundenkarte(card, 3);
        Instant besuch = cc.getLastVisitAt();
        Thread.sleep(5);
        dienst.korrigiereStempel(shop, cc.getId(), 7);
        assertThat(cc.getStamps()).isEqualTo(7);
        assertThat(cc.getLastVisitAt()).isEqualTo(besuch);
        assertThat(cc.getUpdatedAt()).isAfter(besuch);
        verify(ccRepo).save(cc);
    }

    @Test
    void korrektur_gleicherStand_speichertNichts() {
        CustomerCard cc = kundenkarte(card, 3);
        dienst.korrigiereStempel(shop, cc.getId(), 3);
        verify(ccRepo, never()).save(any());
    }

    @Test
    void korrektur_bereich() {
        CustomerCard cc = kundenkarte(card, 3);
        assertThatThrownBy(() -> dienst.korrigiereStempel(shop, cc.getId(), -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0 und 10");
        assertThatThrownBy(() -> dienst.korrigiereStempel(shop, cc.getId(), 11))
                .isInstanceOf(IllegalArgumentException.class);
        dienst.korrigiereStempel(shop, cc.getId(), 10);
        assertThat(cc.getStamps()).isEqualTo(10);
        dienst.korrigiereStempel(shop, cc.getId(), 0);
        assertThat(cc.getStamps()).isZero();
    }

    @Test
    void korrektur_punktekarte_abgelehnt() {
        Card punkte = Card.createPoints(shop, "Punkte", "x", 100, PointsRounding.GENAU);
        CustomerCard cc = kundenkarte(punkte, 0);
        assertThatThrownBy(() -> dienst.korrigiereStempel(shop, cc.getId(), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Scanner");
    }

    @Test
    void korrektur_gesperrt_abgelehnt() {
        CustomerCard cc = kundenkarte(card, 0);
        cc.willkommenVormerken("Erster Kaffee geht auf uns");
        assertThatThrownBy(() -> dienst.korrigiereStempel(shop, cc.getId(), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gesperrt");
    }

    @Test
    void korrektur_fremderLaden_wieNichtVorhanden() {
        Shop fremd = mock(Shop.class);
        when(fremd.getId()).thenReturn("SHOP-2");
        CustomerCard cc = kundenkarte(card, 3);
        assertThatThrownBy(() -> dienst.korrigiereStempel(fremd, cc.getId(), 5))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(cc.getStamps()).isEqualTo(3);
    }

    @Test
    void zeile_fuersDashboard() {
        CustomerCard cc = kundenkarte(card, 4);
        when(deviceRepo.findBySerialNumber(cc.getId()))
                .thenReturn(List.of(mock(AppleDeviceRegistration.class)));
        KundenService.Kunde k = dienst.kunde(cc);
        assertThat(k.name()).isEqualTo("Adham");
        assertThat(k.email()).isEqualTo("adham@test.de");
        assertThat(k.cardName()).isEqualTo("Kaffee");
        assertThat(k.type()).isEqualTo("STAMP");
        assertThat(k.stamps()).isEqualTo(4);
        assertThat(k.rewardThreshold()).isEqualTo(10);
        assertThat(k.lastVisitAt()).isNotNull();
        assertThat(k.apple()).isTrue();
        assertThat(k.welcomePending()).isFalse();
        assertThat(k.emailConfirmed()).isTrue();
    }
}
