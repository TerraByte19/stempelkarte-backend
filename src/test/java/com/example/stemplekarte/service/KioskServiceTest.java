package com.example.stemplekarte.service;

import com.example.stemplekarte.i18n.Texts;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Selbst-Stempeln am Tablet: nur Stempelkarten, nie auf einer vollen Karte
 * (das waere die Praemie), Sperrzeit nach dem letzten Stempel. Meldungen in
 * der Sprache des Kunden. stempeln(): erst Zeilensperre, dann Pruefung, dann
 * genau ein Stempel - oder gar keiner.
 */
class KioskServiceTest {

    private static final Instant JETZT = Instant.parse("2026-11-05T10:00:00Z"); // 11:00 Berlin
    private static final String QR = "{\"cid\":\"CUST-1\",\"cardId\":\"CARD-1\"}";

    private Shop shop;
    private Card card;
    private Customer kunde;
    private CustomerCardRepository ccRepo;
    private CardRepository cardRepo;
    private ScanLogRepository logRepo;
    private CustomerService kundenDienst;
    private KioskService dienst;

    @BeforeEach
    void aufbau() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.getKioskSperreStunden()).thenReturn(2);
        when(shop.getLanguageOrDefault()).thenReturn("tr");
        card = Card.create(shop, "Kahve", "x", 10, "Bir kahve bedava");
        kunde = mock(Customer.class);
        when(kunde.getId()).thenReturn("CUST-1");
        when(kunde.getLanguage()).thenReturn("tr");
        ccRepo = mock(CustomerCardRepository.class);
        cardRepo = mock(CardRepository.class);
        logRepo = mock(ScanLogRepository.class);
        when(cardRepo.findById("CARD-1")).thenReturn(Optional.of(card));
        when(logRepo.findTopByShopIdAndCustomerIdAndCardIdOrderByScannedAtDesc(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        kundenDienst = mock(CustomerService.class);
        dienst = new KioskService(ccRepo, cardRepo, logRepo, kundenDienst);
    }

    private CustomerCard karte(int stempel) {
        CustomerCard cc = CustomerCard.create(kunde, card);
        for (int i = 0; i < stempel; i++) cc.addStamp();
        when(ccRepo.findByCustomer_IdAndCard_Id(eq("CUST-1"), anyString())).thenReturn(Optional.of(cc));
        return cc;
    }

    @Test
    void normalerFall_darf() {
        karte(3);
        assertThatCode(() -> dienst.pruefe(shop, QR, JETZT)).doesNotThrowAnyException();
    }

    @Test
    void ersteKarte_nochNichtAngelegt_darf() {
        when(ccRepo.findByCustomer_IdAndCard_Id(eq("CUST-1"), anyString())).thenReturn(Optional.empty());
        assertThatCode(() -> dienst.pruefe(shop, QR, JETZT)).doesNotThrowAnyException();
    }

    @Test
    void volleKarte_nichtStempeln_praemieGibtDasPersonal() {
        karte(10);
        assertThatThrownBy(() -> dienst.pruefe(shop, QR, JETZT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(Texts.t("tr", "kiosk.full", "Bir kahve bedava"));
    }

    @Test
    void sperrzeit_nachLetztemStempel() {
        karte(3);
        ScanLog letzter = mock(ScanLog.class);
        when(letzter.getScannedAt()).thenReturn(JETZT.minus(Duration.ofMinutes(30)));
        when(logRepo.findTopByShopIdAndCustomerIdAndCardIdOrderByScannedAtDesc(eq("SHOP-1"), eq("CUST-1"), anyString()))
                .thenReturn(Optional.of(letzter));
        // 09:30 UTC + 2 h = 11:30 UTC = 12:30 Berlin
        assertThatThrownBy(() -> dienst.pruefe(shop, QR, JETZT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(Texts.t("tr", "kiosk.locked", "12:30"));

        // Sperre vorbei
        when(letzter.getScannedAt()).thenReturn(JETZT.minus(Duration.ofHours(3)));
        assertThatCode(() -> dienst.pruefe(shop, QR, JETZT)).doesNotThrowAnyException();
    }

    @Test
    void kundeBehaeltSeineSprache() {
        when(kunde.getLanguage()).thenReturn("de");
        karte(10);
        assertThatThrownBy(() -> dienst.pruefe(shop, QR, JETZT))
                .hasMessage("Deine Karte ist voll! Zeig sie dem Personal, dann bekommst du: Bir kahve bedava");
    }

    @Test
    void nurStempelkarten() {
        Card punkte = Card.createPoints(shop, "Puan", "x", 100, com.example.stemplekarte.model.PointsRounding.GENAU);
        when(cardRepo.findById("CARD-1")).thenReturn(Optional.of(punkte));
        karte(0);
        assertThatThrownBy(() -> dienst.pruefe(shop, QR, JETZT))
                .hasMessage(Texts.t("tr", "kiosk.not_stamp"));
    }

    @Test
    void willkommensgeschenkOffen_erstBeimPersonal() {
        CustomerCard cc = CustomerCard.create(kunde, card);
        cc.willkommenVormerken("Erster Kaffee gratis");
        when(ccRepo.findByCustomer_IdAndCard_Id(eq("CUST-1"), anyString())).thenReturn(Optional.of(cc));
        assertThat(cc.isWillkommenOffen()).isTrue();
        assertThatThrownBy(() -> dienst.pruefe(shop, QR, JETZT))
                .hasMessage(Texts.t("tr", "kiosk.welcome"));
    }

    @Test
    void fremderLaden_abgelehnt() {
        Shop anderer = mock(Shop.class);
        when(anderer.getId()).thenReturn("SHOP-2");
        when(anderer.getKioskSperreStunden()).thenReturn(2);
        assertThatThrownBy(() -> dienst.pruefe(anderer, QR, JETZT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stempeln_sperrtZuerst_dannPruefung_dannGenauEinStempel() {
        CustomerCard cc = karte(3);
        ScanResult erwartet = new ScanResult.Stamped(cc, "Stempel hinzugefuegt (4/10)", 0);
        when(kundenDienst.processScan(QR, shop, 1, "Tablet Theke", "ORT-1")).thenReturn(erwartet);

        assertThat(dienst.stempeln(shop, QR, JETZT, "Tablet Theke", "ORT-1")).isSameAs(erwartet);

        // Langer QR: Sperre ueber Kunde + Karte, VOR dem Blick ins Protokoll, dann der Stempel.
        InOrder reihe = inOrder(ccRepo, logRepo, kundenDienst);
        reihe.verify(ccRepo).sperrePerKundeUndKarte("CUST-1", "CARD-1");
        reihe.verify(logRepo).findTopByShopIdAndCustomerIdAndCardIdOrderByScannedAtDesc("SHOP-1", "CUST-1", card.getId());
        reihe.verify(kundenDienst).processScan(QR, shop, 1, "Tablet Theke", "ORT-1");
        verify(ccRepo, never()).sperrePerId(any());
    }

    @Test
    void stempeln_kurzerQr_sperrtUeberKartenId_bevorEtwasGeladenWird() {
        CustomerCard cc = karte(3);
        when(ccRepo.findById(cc.getId())).thenReturn(Optional.of(cc));
        when(cardRepo.findById(card.getId())).thenReturn(Optional.of(card));
        String kurz = QrPayload.fuer(cc);
        when(kundenDienst.processScan(eq(kurz), eq(shop), eq(1), any(), any()))
                .thenReturn(new ScanResult.Stamped(cc, "x", 0));

        dienst.stempeln(shop, kurz, JETZT, null, null);

        InOrder reihe = inOrder(ccRepo, kundenDienst);
        reihe.verify(ccRepo).sperrePerId(cc.getId());
        reihe.verify(ccRepo).findById(cc.getId());
        reihe.verify(kundenDienst).processScan(kurz, shop, 1, null, null);
        verify(ccRepo, never()).sperrePerKundeUndKarte(any(), any());
    }

    @Test
    void stempeln_inDerSperrzeit_keinStempel() {
        karte(3);
        ScanLog letzter = mock(ScanLog.class);
        when(letzter.getScannedAt()).thenReturn(JETZT.minus(Duration.ofMinutes(1)));
        when(logRepo.findTopByShopIdAndCustomerIdAndCardIdOrderByScannedAtDesc(eq("SHOP-1"), eq("CUST-1"), anyString()))
                .thenReturn(Optional.of(letzter));

        assertThatThrownBy(() -> dienst.stempeln(shop, QR, JETZT, "Tablet Theke", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(Texts.t("tr", "kiosk.locked", "12:59"));
        verify(ccRepo).sperrePerKundeUndKarte("CUST-1", "CARD-1");
        verify(kundenDienst, never()).processScan(any(), any(), anyInt(), any(), any());
    }

    @Test
    void stempeln_unsinnImQr_keineSperreKeinStempel() {
        assertThatThrownBy(() -> dienst.stempeln(shop, "SK:nicht gueltig!", JETZT, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.stempeln(shop, "kein json", JETZT, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ccRepo, never()).sperrePerId(any());
        verify(ccRepo, never()).sperrePerKundeUndKarte(any(), any());
        verify(kundenDienst, never()).processScan(any(), any(), anyInt(), any(), any());
    }

    @Test
    void rolleKiosk_darfNurStempeln() {
        StaffToken t = StaffToken.create(shop, "Tablet Theke", "kiosk");
        assertThat(t.getRolle()).isEqualTo(StaffToken.ROLLE_KIOSK);
        assertThat(t.istKiosk()).isTrue();
        assertThat(t.istAushilfe()).isFalse();
        assertThat(t.darfEinloesen()).isFalse();
        assertThat(StaffToken.create(shop, "Ana").darfEinloesen()).isTrue();
        assertThatThrownBy(() -> StaffToken.rolleSauber("CHEF")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sperrzeitEinstellung_standardZwei_grenzen() {
        Shop echt = Shop.create("a@b.de", "hash", "Laden", 3);
        assertThat(echt.getKioskSperreStunden()).isEqualTo(2);
        echt.updateKioskSperre(24);
        assertThat(echt.getKioskSperreStunden()).isEqualTo(24);
    }
}
