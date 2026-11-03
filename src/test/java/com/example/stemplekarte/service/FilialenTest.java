package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Filialen = Hauptstandort + weitere Orte. Woher die Filiale eines Scans
 * kommt, was die Uebersicht zaehlt, und dass Zugaenge nur eigene Orte
 * bekommen.
 */
class FilialenTest {

    private static final Instant JETZT = Instant.parse("2026-11-03T12:00:00Z");

    private Shop shop;
    private ShopOrt markt;
    private ShopOrt bahnhof;
    private ShopOrtRepository ortRepo;
    private ScanLogRepository scanLogRepo;
    private StaffTokenRepository staffRepo;
    private FilialenService dienst;

    @BeforeEach
    void aufbau() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.getLatitude()).thenReturn(52.5200);
        when(shop.getLongitude()).thenReturn(13.4050);
        when(shop.getMaxOrte()).thenReturn(2);
        markt = ShopOrt.create(shop, "Markt", 52.5300, 13.4100);
        bahnhof = ShopOrt.create(shop, "Bahnhof", 48.1370, 11.5750);
        ortRepo = mock(ShopOrtRepository.class);
        when(ortRepo.findByShopOrderByCreatedAtAsc(shop)).thenReturn(List.of(markt, bahnhof));
        scanLogRepo = mock(ScanLogRepository.class);
        staffRepo = mock(StaffTokenRepository.class);
        dienst = new FilialenService(ortRepo, scanLogRepo, staffRepo);
    }

    @Test
    void ortFuer_zugangGewinnt_sonstNaechsterImRadius_sonstHaupt() {
        StaffToken frei = StaffToken.create(shop, "Kasse");
        StaffToken zugeordnet = StaffToken.create(shop, "Markt-Stand");
        zugeordnet.ort(bahnhof.getId());

        // Zuordnung des Zugangs gilt, egal wo das Geraet steht.
        assertThat(dienst.ortFuer(zugeordnet, shop, 52.5200, 13.4050)).isEqualTo(bahnhof.getId());
        // Geraet am Markt (ca. 30 m daneben).
        assertThat(dienst.ortFuer(frei, shop, 52.5302, 13.4102)).isEqualTo(markt.getId());
        // Geraet am Hauptstandort.
        assertThat(dienst.ortFuer(frei, shop, 52.5201, 13.4051)).isNull();
        // Weit weg von allem, kein Ort, kein Tarif: Hauptstandort.
        assertThat(dienst.ortFuer(frei, shop, 50.0, 8.0)).isNull();
        assertThat(dienst.ortFuer(frei, shop, null, null)).isNull();
        when(shop.getMaxOrte()).thenReturn(0);
        assertThat(dienst.ortFuer(frei, shop, 52.5302, 13.4102)).isNull();
        // Tarif erlaubt nur den ersten Ort: Bahnhof zaehlt nicht mehr.
        when(shop.getMaxOrte()).thenReturn(1);
        assertThat(dienst.ortFuer(frei, shop, 48.1371, 11.5751)).isNull();
        assertThat(dienst.ortFuer(frei, shop, 52.5302, 13.4102)).isEqualTo(markt.getId());
    }

    @Test
    void stand_zaehltJeStandort_undGeloeschte() {
        ScanLog haupt1 = ScanLog.create("SHOP-1", "CARD-1", "C-1", 1, 0, "Kasse", null);
        ScanLog haupt2 = ScanLog.create("SHOP-1", "CARD-1", "C-1", 2, 1, "Kasse", null);
        ScanLog m1 = ScanLog.create("SHOP-1", "CARD-1", "C-2", 1, 0, "Stand", markt.getId());
        ScanLog m2 = ScanLog.create("SHOP-1", "CARD-1", "C-3", 1, 0, "Stand", markt.getId());
        ScanLog weg = ScanLog.create("SHOP-1", "CARD-1", "C-4", 1, 0, null, "ORT-WEG");
        when(scanLogRepo.findByShopIdAndScannedAtAfterOrderByScannedAtAsc(eq("SHOP-1"), any()))
                .thenReturn(List.of(haupt1, haupt2, m1, m2, weg));
        StaffToken a = StaffToken.create(shop, "Kasse");
        StaffToken b = StaffToken.create(shop, "Stand");
        b.ort(markt.getId());
        when(staffRepo.findByShop(shop)).thenReturn(List.of(a, b));

        FilialenService.Stand s = dienst.stand(shop, JETZT);
        assertThat(s.max()).isEqualTo(3);
        assertThat(s.genutzt()).isEqualTo(3);
        assertThat(s.filialen()).extracting(FilialenService.Filiale::id)
                .containsExactly("HAUPT", markt.getId(), bahnhof.getId(), "ORT-WEG");

        FilialenService.Filiale h = s.filialen().get(0);
        assertThat(h.haupt()).isTrue();
        assertThat(h.label()).isEqualTo("Hauptstandort");
        assertThat(h.scans30d()).isEqualTo(2);
        assertThat(h.stempel30d()).isEqualTo(3);
        assertThat(h.kunden30d()).isEqualTo(1);
        assertThat(h.zugaenge()).isEqualTo(1);
        assertThat(h.letzterScan()).isNotNull();

        FilialenService.Filiale m = s.filialen().get(1);
        assertThat(m.label()).isEqualTo("Markt");
        assertThat(m.scans30d()).isEqualTo(2);
        assertThat(m.kunden30d()).isEqualTo(2);
        assertThat(m.zugaenge()).isEqualTo(1);

        FilialenService.Filiale bhf = s.filialen().get(2);
        assertThat(bhf.scans30d()).isZero();
        assertThat(bhf.letzterScan()).isNull();

        FilialenService.Filiale g = s.filialen().get(3);
        assertThat(g.geloescht()).isTrue();
        assertThat(g.label()).isEqualTo("Gelöschter Standort");
        assertThat(g.scans30d()).isEqualTo(1);
    }

    @Test
    void team_zugangBekommtNurEigenenOrt() {
        Shop laden = Shop.create("a@b.de", "hash", "Laden", 3);
        ShopOrt eigener = ShopOrt.create(laden, "Nord", 52.5, 13.4);
        when(ortRepo.findByIdAndShop(eigener.getId(), laden)).thenReturn(Optional.of(eigener));
        when(ortRepo.findById(eigener.getId())).thenReturn(Optional.of(eigener));
        when(staffRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        ShopService shopService = mock(ShopService.class);
        StaffToken neu = StaffToken.create(laden, "Nord-Kasse");
        when(shopService.createStaffToken(laden.getId(), "Nord-Kasse", "MITARBEITER")).thenReturn(neu);
        when(staffRepo.findById(neu.getToken())).thenReturn(Optional.of(neu));
        TeamService team = new TeamService(staffRepo, scanLogRepo, shopService, ortRepo);

        StaffToken t = team.anlegen(laden, "Nord-Kasse", null, eigener.getId());
        assertThat(t.getOrtId()).isEqualTo(eigener.getId());
        TeamService.Zugang z = team.zugang(t, laden.getId(), JETZT);
        assertThat(z.ortId()).isEqualTo(eigener.getId());
        assertThat(z.ortLabel()).isEqualTo("Nord");

        // Fremder oder unbekannter Ort: 400.
        assertThatThrownBy(() -> team.aendern(laden, neu.getToken(), null, null, "ORT-FREMD"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Standort");
        // HAUPT = zurueck auf den Hauptstandort; null = nicht anfassen.
        team.aendern(laden, neu.getToken(), null, null, null);
        assertThat(neu.getOrtId()).isEqualTo(eigener.getId());
        team.aendern(laden, neu.getToken(), null, null, "HAUPT");
        assertThat(neu.getOrtId()).isNull();
        assertThat(team.zugang(neu, laden.getId(), JETZT).ortId()).isEqualTo("HAUPT");
        assertThat(team.zugang(neu, laden.getId(), JETZT).ortLabel()).isEqualTo("Hauptstandort");

        // Ort loeschen: Zugang faellt auf den Hauptstandort zurueck.
        neu.ort(eigener.getId());
        ShopRepository shopRepo = mock(ShopRepository.class);
        when(shopRepo.findById(laden.getId())).thenReturn(Optional.of(laden));
        when(staffRepo.findByShop(laden)).thenReturn(List.of(neu));
        new OrteService(ortRepo, shopRepo, staffRepo).loeschen(laden.getId(), eigener.getId());
        assertThat(neu.getOrtId()).isNull();
        verify(ortRepo).delete(eigener);
    }

    @Test
    void scan_protokolliertDieFiliale() {
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis");
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn("CUST-1");
        CustomerCard cc = CustomerCard.create(customer, card);
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        CardRepository cardRepo = mock(CardRepository.class);
        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        when(customerRepo.findById("CUST-1")).thenReturn(Optional.of(customer));
        when(cardRepo.findById("CARD-1")).thenReturn(Optional.of(card));
        when(ccRepo.findByCustomerAndCard(customer, card)).thenReturn(Optional.of(cc));
        when(ccRepo.save(any(CustomerCard.class))).thenAnswer(i -> i.getArgument(0));
        CustomerService service = new CustomerService(customerRepo, cardRepo, ccRepo,
                mock(AppleDeviceRepository.class), mock(EmailService.class), scanLogRepo,
                mock(EmpfehlungService.class));
        WebhookService webhooks = mock(WebhookService.class);
        service.webhooks(webhooks);

        service.processScan("{\"cid\":\"CUST-1\",\"cardId\":\"CARD-1\"}", shop, 1, "Stand", markt.getId());

        ArgumentCaptor<ScanLog> log = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepo).save(log.capture());
        assertThat(log.getValue().getOrtId()).isEqualTo(markt.getId());
        assertThat(log.getValue().getStaffLabel()).isEqualTo("Stand");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> daten = ArgumentCaptor.forClass(java.util.Map.class);
        verify(webhooks).melde(eq(shop), eq("stamp.added"), daten.capture());
        assertThat(daten.getValue()).containsEntry("locationId", markt.getId());

        // Ohne Filiale: kein Eintrag im Webhook, null im Protokoll.
        assertThat(ScanLog.create("S", "C", "K", 1, 0, "x", " ").getOrtId()).isNull();
        assertThat(ScanLog.create("S", "C", "K", 1, 0).getOrtId()).isNull();
        assertThat(Duration.between(JETZT, JETZT)).isZero();
    }
}
