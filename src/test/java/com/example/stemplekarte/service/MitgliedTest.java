package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CardType;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mitgliedskarte: ein Scan = ein Besuch, keine Stempel, keine Praemie,
 * kein Geschenk, keine Empfehlung. Was trotzdem geht: Stufen, Gruppen,
 * Willkommens-Automat.
 */
class MitgliedTest {

    private static final String QR = "{\"cid\":\"CUST-1\",\"cardId\":\"CARD-M\"}";

    private Shop shop;
    private Card card;
    private CustomerCard cc;
    private ScanLogRepository scanLogRepo;
    private CustomerService service;

    @BeforeEach
    void aufbau() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        card = Card.createMember(shop, "Clubkarte", "Mitgliedsausweis");
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn("CUST-1");
        when(customer.getName()).thenReturn("Anna");
        cc = CustomerCard.create(customer, card);

        CustomerRepository customerRepo = mock(CustomerRepository.class);
        CardRepository cardRepo = mock(CardRepository.class);
        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        scanLogRepo = mock(ScanLogRepository.class);
        when(customerRepo.findById("CUST-1")).thenReturn(Optional.of(customer));
        when(cardRepo.findById("CARD-M")).thenReturn(Optional.of(card));
        when(ccRepo.findByCustomerAndCard(customer, card)).thenReturn(Optional.of(cc));
        when(ccRepo.save(any(CustomerCard.class))).thenAnswer(i -> i.getArgument(0));
        service = new CustomerService(customerRepo, cardRepo, ccRepo,
                mock(AppleDeviceRepository.class), mock(EmailService.class), scanLogRepo,
                mock(EmpfehlungService.class));
    }

    @Test
    void karte_typUndRegeln() {
        assertThat(card.getType()).isEqualTo(CardType.MEMBER);
        assertThat(card.isMember()).isTrue();
        assertThat(card.isStamp()).isFalse();
        assertThat(card.isPoints()).isFalse();
        // Geschenk nie, auch wenn die Spalte gesetzt wird.
        card.updateWillkommen(true, "Gratis");
        assertThat(card.isWillkommenAktiv()).isFalse();
        // Stufen nach Besuchen: ja.
        card.updateStufen(true, null, null, 5, null, 20);
        assertThat(card.isStufenAktiv()).isTrue();
    }

    @Test
    void scan_zaehltBesuch_ohneStempelUndPraemie() {
        ScanResult r = service.processScan(QR, shop, 1);
        assertThat(r).isInstanceOf(ScanResult.Visited.class);
        assertThat(r.message()).isEqualTo("Besuch erfasst (1)");
        assertThat(r.rewardsEarnedThisScan()).isZero();
        assertThat(cc.getStamps()).isEqualTo(1);
        assertThat(cc.getLastVisitAt()).isNotNull();

        // Anzahl vom Scanner zaehlt nicht: ein Scan, ein Besuch.
        service.processScan(QR, shop, 5);
        assertThat(cc.getStamps()).isEqualTo(2);
        assertThat(cc.getTotalRewards()).isZero();

        // Protokoll ohne Stempel, wie bei der Punktekarte.
        ArgumentCaptor<ScanLog> log = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepo, org.mockito.Mockito.times(2)).save(log.capture());
        assertThat(log.getValue().getStampsAdded()).isZero();
    }

    @Test
    void nieVoll_auchNachVielenBesuchen() {
        for (int i = 0; i < 30; i++) service.processScan(QR, shop, 1);
        assertThat(cc.getStamps()).isEqualTo(30);
        assertThat(cc.getTotalRewards()).isZero();
        Instant jetzt = Instant.now();
        assertThat(Zielgruppe.PRAEMIE_WARTET.passt(cc, jetzt, c -> List.of())).isFalse();
        assertThat(Zielgruppe.FAST_VOLL.passt(cc, jetzt, c -> List.of())).isFalse();
        assertThat(Zielgruppe.AUTO_FAST_VOLL.passt(cc, jetzt.plus(Duration.ofDays(3)), c -> List.of())).isFalse();
        assertThat(Zielgruppe.AUTO_PRAEMIE.passt(cc, jetzt.plus(Duration.ofDays(3)), c -> List.of())).isFalse();
        // Willkommens-Automat und Gruppen funktionieren ueber die Besuche.
        assertThat(Zielgruppe.AUTO_VIP.passt(cc, jetzt, c -> List.of())).isTrue();
        assertThat(Kundengruppe.von(cc, Kundengruppe.besucheStempelkarte(card, cc), Kundengruppe.Regeln.STANDARD, jetzt))
                .isEqualTo(Kundengruppe.STAMMGAST);
    }

    @Test
    void keineEmpfehlung_keinZiel_keineKorrektur() {
        assertThat(Platzhalter.fuelle("Noch {offen} Stempel bis: {praemie}!", cc, c -> List.of()))
                .isEqualTo("Noch Stempel bis!");
        assertThat(KartenFortschritt.von(card, cc, mock(RewardService.class))).isNull();
        assertThat(EmpfehlungService.sichtbar(card, cc)).isFalse();

        EmpfehlungService empfehlung = new EmpfehlungService(mock(CustomerCardRepository.class),
                mock(com.example.stemplekarte.config.AppProperties.class),
                mock(com.example.stemplekarte.wallet.WalletNotifier.class));
        assertThatThrownBy(() -> empfehlung.einstellen(card, true, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Stempel- und Punktekarten");

        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        when(ccRepo.findById(cc.getId())).thenReturn(Optional.of(cc));
        KundenService kunden = new KundenService(ccRepo, mock(AppleDeviceRepository.class),
                mock(ScanLogRepository.class), mock(com.example.stemplekarte.repository.ShopRepository.class));
        assertThatThrownBy(() -> kunden.korrigiereStempel(shop, cc.getId(), 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Mitgliedskarte");
    }
}
