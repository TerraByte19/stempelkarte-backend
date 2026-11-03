package com.example.stemplekarte.service;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.wallet.GoogleWalletService;
import com.example.stemplekarte.wallet.WalletNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Demo-Karten: nur Demo-Laeden, jeder Schritt macht das Naechste an der
 * Kasse, Drossel je Karte, Aufraeumen nach einer Woche.
 */
class DemoTest {

    private static final Instant JETZT = Instant.parse("2026-11-03T12:00:00Z");

    private Shop demo;
    private Card karte;
    private Customer kunde;
    private CustomerCard cc;
    private CardRepository cardRepo;
    private CustomerCardRepository ccRepo;
    private ShopRepository shopRepo;
    private AppleDeviceRepository geraete;
    private CustomerService customerService;
    private PointsService pointsService;
    private PaketService paketService;
    private WalletNotifier notifier;
    private DemoService dienst;

    @BeforeEach
    void aufbau() {
        demo = mock(Shop.class);
        when(demo.getId()).thenReturn("SHOP-DEMO");
        when(demo.getName()).thenReturn("Demo Café");
        when(demo.isDemo()).thenReturn(true);
        when(demo.isActive()).thenReturn(true);
        karte = Card.create(demo, "Kaffee", "x", 10, "Gratis Kaffee");
        kunde = mock(Customer.class);
        when(kunde.getId()).thenReturn("CUST-1");
        cc = CustomerCard.create(kunde, karte);

        cardRepo = mock(CardRepository.class);
        when(cardRepo.findById(karte.getId())).thenReturn(Optional.of(karte));
        when(cardRepo.findByShopAndActiveTrue(demo)).thenReturn(List.of(karte));
        when(cardRepo.findByShop(demo)).thenReturn(List.of(karte));
        ccRepo = mock(CustomerCardRepository.class);
        shopRepo = mock(ShopRepository.class);
        when(shopRepo.findByDemoTrue()).thenReturn(List.of(demo));
        geraete = mock(AppleDeviceRepository.class);
        customerService = mock(CustomerService.class);
        when(customerService.getOrCreateCustomerCard("CUST-1", karte.getId())).thenReturn(cc);
        pointsService = mock(PointsService.class);
        paketService = mock(PaketService.class);
        notifier = mock(WalletNotifier.class);
        AppProperties props = mock(AppProperties.class);
        when(props.baseUrl()).thenReturn("https://stampit.test");
        dienst = new DemoService(shopRepo, cardRepo, ccRepo, geraete, customerService, pointsService,
                paketService, notifier, props);
    }

    @Test
    void karten_nurDemoLaeden_mitLink() {
        List<DemoService.DemoKarte> l = dienst.karten();
        assertThat(l).hasSize(1);
        assertThat(l.get(0).url()).isEqualTo("https://stampit.test/karte-neu/" + karte.getId());
        assertThat(l.get(0).shopName()).isEqualTo("Demo Café");
        assertThat(l.get(0).type()).isEqualTo("STAMP");
    }

    @Test
    void schritt_nurDemo_dannStempel() {
        when(customerService.processScan(anyString(), eq(demo), anyInt(), eq("Demo")))
                .thenAnswer(i -> { cc.addStamp(); return new ScanResult.Stamped(cc, "Stempel hinzugefuegt (1/10)", 0); });
        DemoService.Ergebnis e = dienst.schritt("CUST-1", karte.getId(), JETZT);
        assertThat(e.action()).isEqualTo("stamped");
        assertThat(e.stamps()).isEqualTo(1);
        assertThat(e.rewardThreshold()).isEqualTo(10);
        verify(notifier).nachStempelAenderung(cc, GoogleWalletService.Meldung.STEMPEL);

        // Kein Demo-Laden: nichts.
        when(demo.isDemo()).thenReturn(false);
        assertThatThrownBy(() -> dienst.schritt("CUST-1", karte.getId(), JETZT.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Demo");
        // Unsaubere IDs: nichts.
        assertThatThrownBy(() -> dienst.schritt("x y", karte.getId(), JETZT.plusSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schritt_geschenkZuerst_punkteUndPaket() {
        // Gesperrt: erst das Geschenk.
        cc.willkommenVormerken("Gratis");
        when(customerService.willkommenEinloesen(anyString(), eq(demo))).thenAnswer(i -> { cc.willkommenEinloesen(JETZT); return cc; });
        DemoService.Ergebnis e = dienst.schritt("CUST-1", karte.getId(), JETZT);
        assertThat(e.action()).isEqualTo("welcome");
        verify(notifier).nachFreischaltung(cc);
        verify(customerService, never()).processScan(anyString(), any(), anyInt(), anyString());

        // Punktekarte: Einkauf buchen.
        Card punkte = Card.createPoints(demo, "Punkte", "x", 100, com.example.stemplekarte.model.PointsRounding.GENAU);
        when(cardRepo.findById(punkte.getId())).thenReturn(Optional.of(punkte));
        CustomerCard pcc = CustomerCard.create(kunde, punkte);
        when(customerService.getOrCreateCustomerCard("CUST-1", punkte.getId())).thenReturn(pcc);
        PointsService.PointsResult pr = new PointsService.PointsResult(pcc.getId(), "Anna", punkte.getId(), "Punkte",
                1250, "12,50", null, null, 0, "0", false, List.of());
        when(pointsService.earn(anyString(), eq(demo), eq(1250L), eq("Demo"))).thenReturn(pr);
        e = dienst.schritt("CUST-1", punkte.getId(), JETZT);
        assertThat(e.action()).isEqualTo("points");
        assertThat(e.pointsX100()).isEqualTo(1250);
        verify(notifier).nachPunkteAenderung(eq(pcc.getId()), eq(1250L), any(), eq(0L));

        // 10er-Karte leer: erst aufladen, dann abbuchen.
        Card paket = Card.createPackage(demo, "Yoga", "x", 10, "Kurse");
        when(cardRepo.findById(paket.getId())).thenReturn(Optional.of(paket));
        CustomerCard kcc = CustomerCard.create(kunde, paket);
        when(customerService.getOrCreateCustomerCard("CUST-1", paket.getId())).thenReturn(kcc);
        when(paketService.aufladen(anyString(), eq(demo), eq("Demo"))).thenAnswer(i -> { kcc.paketAufladen(10, JETZT); return kcc; });
        e = dienst.schritt("CUST-1", paket.getId(), JETZT);
        assertThat(e.action()).isEqualTo("topup");
        assertThat(e.paketRest()).isEqualTo(10);
        when(customerService.processScan(anyString(), eq(demo), anyInt(), eq("Demo")))
                .thenAnswer(i -> { kcc.paketAbbuchen(1, JETZT); return new ScanResult.Debited(kcc, "Abgebucht", 0); });
        e = dienst.schritt("CUST-1", paket.getId(), JETZT.plusSeconds(10));
        assertThat(e.action()).isEqualTo("debited");
        assertThat(e.paketRest()).isEqualTo(9);
        verify(notifier).nachStempelAenderung(kcc, GoogleWalletService.Meldung.PAKET);
    }

    @Test
    void drossel_abstandUndTageslimit() {
        dienst.drosseln("CC-1", JETZT);
        assertThatThrownBy(() -> dienst.drosseln("CC-1", JETZT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("warten");
        for (int i = 1; i < DemoService.MAX_JE_KARTE_UND_TAG; i++) dienst.drosseln("CC-1", JETZT.plusSeconds(10L * i));
        assertThatThrownBy(() -> dienst.drosseln("CC-1", JETZT.plus(Duration.ofHours(1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("morgen");
        // Andere Karte unabhaengig; nach 24 Stunden wieder frei.
        dienst.drosseln("CC-2", JETZT);
        dienst.drosseln("CC-1", JETZT.plus(Duration.ofHours(25)));
    }

    @Test
    void aufraeumen_nachEinerWoche_kundeMitWennSonstNichts() {
        CustomerCard alt = CustomerCard.create(kunde, karte);
        CustomerCard frisch = CustomerCard.create(mock(Customer.class), karte);
        // createdAt laesst sich nicht setzen: alt per Mock.
        CustomerCard altMock = mock(CustomerCard.class);
        when(altMock.getId()).thenReturn("CC-ALT");
        when(altMock.getCreatedAt()).thenReturn(JETZT.minus(Duration.ofDays(8)));
        when(altMock.getCustomer()).thenReturn(kunde);
        when(ccRepo.findByCard(karte)).thenReturn(List.of(altMock, frisch));
        when(ccRepo.findByCustomer(kunde)).thenReturn(List.of(altMock));

        assertThat(dienst.aufraeumen(JETZT)).isEqualTo(1);
        verify(customerService).deleteCustomerData("CUST-1");
        verify(ccRepo, never()).delete(any());

        // Hat noch eine echte Karte woanders: nur die Demo-Karte weg.
        Shop echt = mock(Shop.class);
        Card echteKarte = Card.create(echt, "Echt", "x", 10, "x");
        CustomerCard echteCc = CustomerCard.create(kunde, echteKarte);
        when(ccRepo.findByCustomer(kunde)).thenReturn(List.of(altMock, echteCc));
        assertThat(dienst.aufraeumen(JETZT)).isEqualTo(1);
        verify(ccRepo).delete(altMock);
        verify(geraete).deleteBySerialNumber("CC-ALT");
        assertThat(alt.getCreatedAt()).isNotNull();
    }
}
