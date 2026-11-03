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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 10er-Karte: Paket kaufen, Besuche abbuchen, leer = nichts buchen. Was
 * trotzdem geht: Stufen, Gruppen, Zielgruppe "Paket leer" und der Automat.
 */
class PaketTest {

    private static final String QR = "{\"cid\":\"CUST-1\",\"cardId\":\"CARD-P\"}";

    private Shop shop;
    private Card card;
    private CustomerCard cc;
    private CustomerCardRepository ccRepo;
    private ScanLogRepository scanLogRepo;
    private WebhookService webhooks;
    private CustomerService service;
    private PaketService pakete;

    @BeforeEach
    void aufbau() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        card = Card.createPackage(shop, "Yoga 10er", "10 Kurse", 10, "Kurse");
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn("CUST-1");
        when(customer.getName()).thenReturn("Anna");
        cc = CustomerCard.create(customer, card);

        CustomerRepository customerRepo = mock(CustomerRepository.class);
        CardRepository cardRepo = mock(CardRepository.class);
        ccRepo = mock(CustomerCardRepository.class);
        scanLogRepo = mock(ScanLogRepository.class);
        when(customerRepo.findById("CUST-1")).thenReturn(Optional.of(customer));
        when(cardRepo.findById("CARD-P")).thenReturn(Optional.of(card));
        when(ccRepo.findByCustomerAndCard(customer, card)).thenReturn(Optional.of(cc));
        when(ccRepo.findById(cc.getId())).thenReturn(Optional.of(cc));
        when(ccRepo.save(any(CustomerCard.class))).thenAnswer(i -> i.getArgument(0));
        service = new CustomerService(customerRepo, cardRepo, ccRepo,
                mock(AppleDeviceRepository.class), mock(EmailService.class), scanLogRepo,
                mock(EmpfehlungService.class));
        webhooks = mock(WebhookService.class);
        service.webhooks(webhooks);
        pakete = new PaketService(cardRepo, ccRepo, service);
        pakete.webhooks(webhooks);
    }

    @Test
    void karte_typUndRegeln() {
        assertThat(card.getType()).isEqualTo(CardType.PACKAGE);
        assertThat(card.isPackage()).isTrue();
        assertThat(card.isStamp()).isFalse();
        assertThat(card.isMember()).isFalse();
        assertThat(card.getRewardThreshold()).isEqualTo(10);
        assertThat(card.getPaketEinheit()).isEqualTo("Kurse");
        assertThat(card.paketText()).isEqualTo("10 Kurse");
        // Ohne Einheit: Besuche.
        assertThat(Card.createPackage(shop, "x", "y", 5, " ").getPaketEinheit()).isEqualTo("Besuche");
        // Geschenk nie, Stufen nach Besuchen ja.
        card.updateWillkommen(true, "Gratis");
        assertThat(card.isWillkommenAktiv()).isFalse();
        card.updateStufen(true, null, null, 5, null, 20);
        assertThat(card.isStufenAktiv()).isTrue();
        // Bereich.
        assertThatThrownBy(() -> PaketService.pruefe(0, "Kurse")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaketService.pruefe(101, "Kurse")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaketService.pruefe(10, "x".repeat(21))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaketService.pruefe(10, "<b>")).isInstanceOf(IllegalArgumentException.class);
        assertThat(PaketService.pruefe(10, "  ").einheit()).isNull();
        assertThat(PaketService.pruefe(12, " Wäschen ").einheit()).isEqualTo("Wäschen");
    }

    @Test
    void leer_dannAufladen_dannAbbuchen() {
        // Nie geladen: nichts buchen, kein Besuch, kein Protokoll, kein Webhook.
        ScanResult leer = service.processScan(QR, shop, 1);
        assertThat(leer).isInstanceOf(ScanResult.Empty.class);
        assertThat(leer.message()).contains("Paket leer").contains("10 Kurse");
        assertThat(cc.getStamps()).isZero();
        assertThat(cc.getLastVisitAt()).isNull();
        verify(scanLogRepo, never()).save(any());
        verify(webhooks, never()).melde(any(), any(), any());

        // Aufladen an der Kasse.
        CustomerCard geladen = pakete.aufladen(QR, shop, "Lena");
        assertThat(geladen).isSameAs(cc);
        assertThat(cc.getPaketRest()).isEqualTo(10);
        assertThat(cc.getPaketeGekauft()).isEqualTo(1);
        assertThat(cc.getPaketAufgeladenAm()).isNotNull();
        assertThat(cc.getLastVisitAt()).isNotNull();
        assertThat(cc.paketFrischAufgeladen()).isTrue();
        assertThat(PaketService.aufgeladenText(card, cc)).isEqualTo("Paket aufgeladen · noch 10 Kurse übrig");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> daten = ArgumentCaptor.forClass(Map.class);
        verify(webhooks).melde(eq(shop), eq("package.topup"), daten.capture());
        assertThat(daten.getValue()).containsEntry("remaining", 10).containsEntry("packagesBought", 1)
                .containsEntry("staff", "Lena");

        // Ein Besuch.
        ScanResult r = service.processScan(QR, shop, 1);
        assertThat(r).isInstanceOf(ScanResult.Debited.class);
        assertThat(r.message()).isEqualTo("Abgebucht · noch 9 Kurse übrig");
        assertThat(r.rewardsEarnedThisScan()).isZero();
        assertThat(cc.getPaketRest()).isEqualTo(9);
        assertThat(cc.getStamps()).isEqualTo(1);
        assertThat(cc.getTotalRewards()).isZero();
        assertThat(cc.paketFrischAufgeladen()).isFalse();
        verify(webhooks).melde(eq(shop), eq("package.debited"), daten.capture());
        assertThat(daten.getValue()).containsEntry("remaining", 9).containsEntry("stampsAdded", 1);

        // Mehr als uebrig: nur der Rest wird gebucht, dann ist es leer.
        ScanResult alles = service.processScan(QR, shop, 20);
        assertThat(alles).isInstanceOf(ScanResult.Debited.class);
        assertThat(alles.message()).isEqualTo("9 abgebucht · Paket leer, neues Paket an der Kasse");
        assertThat(cc.getPaketRest()).isZero();
        assertThat(cc.getStamps()).isEqualTo(10);
        assertThat(PaketService.restText(card, cc)).isEqualTo("Paket leer");

        // Danach wieder leer.
        assertThat(service.processScan(QR, shop, 1)).isInstanceOf(ScanResult.Empty.class);

        // Protokoll: zwei Besuche, beide ohne Stempel.
        ArgumentCaptor<ScanLog> log = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepo, times(2)).save(log.capture());
        assertThat(log.getAllValues()).allSatisfy(l -> assertThat(l.getStampsAdded()).isZero());
    }

    @Test
    void aufladen_addiert_undDeckelt() {
        pakete.aufladen(QR, shop, "Lena");
        service.processScan(QR, shop, 8);
        assertThat(cc.getPaketRest()).isEqualTo(2);
        pakete.aufladen(shop, cc.getId());
        assertThat(cc.getPaketRest()).isEqualTo(12);
        assertThat(cc.getPaketeGekauft()).isEqualTo(2);
        assertThat(PaketService.restText(card, cc)).isEqualTo("Noch 12 Kurse übrig");

        cc.korrigierePaketRest(495);
        assertThatThrownBy(() -> pakete.aufladen(shop, cc.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");

        // Nur 10er-Karten.
        Card stempel = Card.create(shop, "Kaffee", "x", 10, "Gratis");
        CustomerCard sc = CustomerCard.create(cc.getCustomer(), stempel);
        when(ccRepo.findById(sc.getId())).thenReturn(Optional.of(sc));
        assertThatThrownBy(() -> pakete.aufladen(shop, sc.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10er-Karte");
        // Fremder Laden: nicht gefunden.
        Shop anderer = mock(Shop.class);
        when(anderer.getId()).thenReturn("SHOP-2");
        assertThatThrownBy(() -> pakete.aufladen(anderer, cc.getId()))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void einUebrig_text() {
        pakete.aufladen(QR, shop, null);
        service.processScan(QR, shop, 9);
        assertThat(PaketService.restText(card, cc)).isEqualTo("Noch 1 übrig");
        assertThat(Platzhalter.fuelle("Noch {offen} übrig, {name}!", cc, c -> List.of())).isEqualTo("Noch 1 übrig, Anna!");
    }

    @Test
    void zielgruppen_undAutomat() {
        Instant jetzt = Instant.now();
        // Nie gekauft: nicht "leer".
        assertThat(Zielgruppe.PAKET_LEER.passt(cc, jetzt, c -> List.of())).isFalse();
        assertThat(Zielgruppe.PAKET_LEER.waehlbar()).isTrue();
        assertThat(Zielgruppe.AUTO_PAKET_LEER.waehlbar()).isFalse();

        pakete.aufladen(QR, shop, null);
        service.processScan(QR, shop, 10);
        assertThat(cc.getPaketRest()).isZero();
        assertThat(Zielgruppe.PAKET_LEER.passt(cc, jetzt, c -> List.of())).isTrue();
        assertThat(Zielgruppe.PRAEMIE_WARTET.passt(cc, jetzt, c -> List.of())).isFalse();
        assertThat(Zielgruppe.FAST_VOLL.passt(cc, jetzt, c -> List.of())).isFalse();
        assertThat(Zielgruppe.AUTO_PRAEMIE.passt(cc, jetzt.plus(Duration.ofDays(3)), c -> List.of())).isFalse();

        // Automat: nicht sofort, erst nach 2 Tagen, bis 14 Tage.
        assertThat(Zielgruppe.AUTO_PAKET_LEER.passt(cc, jetzt, c -> List.of())).isFalse();
        Instant in3Tagen = jetzt.plus(Duration.ofDays(3));
        assertThat(Zielgruppe.AUTO_PAKET_LEER.passt(cc, in3Tagen, c -> List.of())).isTrue();
        assertThat(Zielgruppe.AUTO_PAKET_LEER.passt(cc, jetzt.plus(Duration.ofDays(20)), c -> List.of())).isFalse();
        assertThat(AutoNachrichtTyp.PAKET_LEER.markiert(cc)).isFalse();
        AutoNachrichtTyp.PAKET_LEER.markiere(cc, in3Tagen);
        assertThat(AutoNachrichtTyp.PAKET_LEER.markiert(cc)).isTrue();
        assertThat(Zielgruppe.AUTO_PAKET_LEER.passt(cc, in3Tagen, c -> List.of())).isFalse();
        // Neues Paket, wieder leer: wieder dran.
        pakete.aufladen(QR, shop, null);
        assertThat(Zielgruppe.PAKET_LEER.passt(cc, in3Tagen, c -> List.of())).isFalse();
        service.processScan(QR, shop, 10);
        assertThat(AutoNachrichtTyp.PAKET_LEER.markiert(cc)).isFalse();
        assertThat(Zielgruppe.AUTO_PAKET_LEER.passt(cc, in3Tagen.plus(Duration.ofDays(3)), c -> List.of())).isTrue();

        // Gruppen und Stufen rechnen mit den Besuchen (stamps).
        assertThat(Kundengruppe.besucheStempelkarte(card, cc)).isEqualTo(20);
        assertThat(Kundengruppe.von(cc, Kundengruppe.besucheStempelkarte(card, cc), Kundengruppe.Regeln.STANDARD, jetzt))
                .isEqualTo(Kundengruppe.STAMMGAST);
    }

    @Test
    void keinZiel_keineEmpfehlung_korrekturNurGuthaben() {
        assertThat(KartenFortschritt.von(card, cc, mock(RewardService.class))).isNull();
        assertThat(EmpfehlungService.sichtbar(card, cc)).isFalse();
        EmpfehlungService empfehlung = new EmpfehlungService(mock(CustomerCardRepository.class),
                mock(com.example.stemplekarte.config.AppProperties.class),
                mock(com.example.stemplekarte.wallet.WalletNotifier.class));
        assertThatThrownBy(() -> empfehlung.einstellen(card, true, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Stempel- und Punktekarten");

        KundenService kunden = new KundenService(ccRepo, mock(AppleDeviceRepository.class),
                mock(ScanLogRepository.class), mock(com.example.stemplekarte.repository.ShopRepository.class));
        assertThatThrownBy(() -> kunden.korrigiereStempel(shop, cc.getId(), 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Guthaben");
        assertThatThrownBy(() -> kunden.korrigierePaket(shop, cc.getId(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kunden.korrigierePaket(shop, cc.getId(), 501))
                .isInstanceOf(IllegalArgumentException.class);
        kunden.korrigierePaket(shop, cc.getId(), 4);
        assertThat(cc.getPaketRest()).isEqualTo(4);
        assertThat(cc.getPaketeGekauft()).isZero();
        assertThat(cc.getLastVisitAt()).isNull();
        // Zeile der Kundenliste traegt den Rest.
        when(ccRepo.findByCustomer(any())).thenReturn(List.of());
        KundenService.Kunde zeile = kunden.kunde(cc);
        assertThat(zeile.paketRest()).isEqualTo(4);
        assertThat(zeile.type()).isEqualTo("PACKAGE");
    }
}
