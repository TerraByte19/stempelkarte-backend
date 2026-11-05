package com.example.stemplekarte.service;

import com.example.stemplekarte.i18n.Texts;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Selbst-Stempeln am Tablet: nur Stempelkarten, nie auf einer vollen Karte
 * (das waere die Praemie), Sperrzeit nach dem letzten Stempel. Meldungen in
 * der Sprache des Kunden.
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
        ccRepo = mock(CustomerCardRepository.class);
        cardRepo = mock(CardRepository.class);
        logRepo = mock(ScanLogRepository.class);
        when(cardRepo.findById("CARD-1")).thenReturn(Optional.of(card));
        when(logRepo.findTopByShopIdAndCustomerIdAndCardIdOrderByScannedAtDesc(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        dienst = new KioskService(ccRepo, cardRepo, logRepo);
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
