package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.example.stemplekarte.repository.PointsBookingRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vergleich zum Vorzeitraum und "Zuletzt passiert".
 *
 * Die Fenster muessen sauber trennen: ein Scan von vor 10 Tagen zaehlt zur
 * Vorwoche UND zum Monat, einer von vor 40 Tagen nur zum Vormonat - und
 * taucht weder im Verlauf noch in "staerkster Tag" auf.
 */
class VorzeitraumTest {

    private final Instant jetzt = Instant.now();
    private Shop shop;
    private ScanLogRepository scanLogRepo;
    private CustomerRepository customerRepo;
    private CardRepository cardRepo;
    private CustomerCardRepository ccRepo;
    private StatsService dienst;

    @BeforeEach
    void setUp() {
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.getName()).thenReturn("Café Mokka");
        CardService cardService = mock(CardService.class);
        when(cardService.getByShop(shop)).thenReturn(List.of());
        cardRepo = mock(CardRepository.class);
        when(cardRepo.findByShop(shop)).thenReturn(List.of());
        ccRepo = mock(CustomerCardRepository.class);
        customerRepo = mock(CustomerRepository.class);
        scanLogRepo = mock(ScanLogRepository.class);
        PointsBookingRepository buchungen = mock(PointsBookingRepository.class);
        when(buchungen.findByShopIdAndCreatedAtAfterOrderByCreatedAtAsc(anyString(), any())).thenReturn(List.of());
        dienst = new StatsService(cardService, cardRepo, ccRepo, customerRepo, scanLogRepo, buchungen);
    }

    private ScanLog scan(int vorTagen, int stempel, int praemien) {
        ScanLog sl = mock(ScanLog.class);
        when(sl.getScannedAt()).thenReturn(jetzt.minus(Duration.ofDays(vorTagen)).plusSeconds(60));
        when(sl.getStampsAdded()).thenReturn(stempel);
        when(sl.getRewardsEarned()).thenReturn(praemien);
        when(sl.getCustomerId()).thenReturn("CUST-1");
        when(sl.getCardId()).thenReturn("CARD-1");
        return sl;
    }

    @Test
    void wochenUndMonatsFensterTrennenSauber() {
        // Erst bauen, dann stubben: ein scan() innerhalb von thenReturn()
        // waere ein Stub im Stub, das lehnt Mockito ab.
        List<ScanLog> scans = List.of(
                scan(1, 3, 1),    // diese Woche
                scan(10, 5, 0),   // Vorwoche, dieser Monat
                scan(20, 7, 2),   // nur dieser Monat
                scan(40, 11, 4)); // Vormonat
        when(scanLogRepo.findByShopIdAndScannedAtAfterOrderByScannedAtAsc(anyString(), any()))
                .thenReturn(scans);
        Map<String, Object> s = dienst.summary(shop);
        assertThat(s.get("stampsThisWeek")).isEqualTo(3);
        assertThat(s.get("stampsLastWeek")).isEqualTo(5);
        assertThat(s.get("stampsThisMonth")).isEqualTo(15);
        assertThat(s.get("stampsPrevMonth")).isEqualTo(11);
        assertThat(s.get("rewardsThisWeek")).isEqualTo(1);
        assertThat(s.get("rewardsLastWeek")).isEqualTo(0);
        assertThat(s.get("rewardsThisMonth")).isEqualTo(3);
        assertThat(s.get("rewardsPrevMonth")).isEqualTo(4);
        // Der Vormonat-Scan darf den 2-Wochen-Verlauf nicht fuellen.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> verlauf = (List<Map<String, Object>>) s.get("history");
        int summe = verlauf.stream().mapToInt(d -> (int) d.get("stamps")).sum();
        assertThat(summe).isEqualTo(8);
    }

    @Test
    void aktivitaet_mitNamen_undNeuenKunden() {
        List<ScanLog> juengste = List.of(scan(0, 2, 0), scan(1, 0, 1));
        when(scanLogRepo.findTop15ByShopIdOrderByScannedAtDesc("SHOP-1")).thenReturn(juengste);
        Customer kunde = mock(Customer.class);
        when(kunde.getId()).thenReturn("CUST-1");
        when(kunde.getName()).thenReturn("Adham");
        when(customerRepo.findAllById(any())).thenReturn(List.of(kunde));
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        when(cardRepo.findAllById(any())).thenReturn(List.of(card));
        // Die Scans muessen auf DIESE Karte zeigen, sonst bleibt der Name leer.
        for (ScanLog sl : juengste) when(sl.getCardId()).thenReturn(card.getId());
        CustomerCard neu = CustomerCard.create(kunde, card);
        when(ccRepo.findTop5ByCard_ShopOrderByCreatedAtDesc(shop)).thenReturn(List.of(neu));

        Map<String, Object> a = dienst.aktivitaet(shop);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scans = (List<Map<String, Object>>) a.get("scans");
        assertThat(scans).hasSize(2);
        assertThat(scans.get(0).get("kunde")).isEqualTo("Adham");
        assertThat(scans.get(0).get("karte")).isEqualTo("Kaffee");
        assertThat(scans.get(0).get("stempel")).isEqualTo(2);
        assertThat(scans.get(1).get("praemien")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> neue = (List<Map<String, Object>>) a.get("neueKunden");
        assertThat(neue).hasSize(1);
        assertThat(neue.get(0).get("name")).isEqualTo("Adham");
        assertThat(neue.get(0).get("gesperrt")).isEqualTo(false);
    }

    @Test
    void praemienRangliste_ueberBeideKartentypen() {
        Card kaffee = Card.create(shop, "Kaffeekarte", "x", 10, "Gratis Kaffee");
        Card tee = Card.create(shop, "Teekarte", "x", 5, "Gratis Tee");
        when(cardRepo.findByShop(shop)).thenReturn(List.of(kaffee, tee));
        when(ccRepo.findByCard(any())).thenReturn(List.of());
        List<ScanLog> scans = List.of(scan(1, 1, 1), scan(2, 1, 1), scan(3, 1, 1), scan(40, 1, 9));
        for (ScanLog sl : scans) when(sl.getCardId()).thenReturn(kaffee.getId());
        when(sl(scans.get(2)).getCardId()).thenReturn(tee.getId());
        when(scanLogRepo.findByShopIdAndScannedAtAfterOrderByScannedAtAsc(anyString(), any())).thenReturn(scans);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) dienst.summary(shop).get("topRewardsAll");
        // Kaffee 2 (der Vormonat-Scan zaehlt nicht), Tee 1.
        assertThat(top).hasSize(2);
        assertThat(top.get(0).get("name")).isEqualTo("Gratis Kaffee");
        assertThat(top.get(0).get("card")).isEqualTo("Kaffeekarte");
        assertThat(top.get(0).get("count")).isEqualTo(2L);
        assertThat(top.get(1).get("name")).isEqualTo("Gratis Tee");
    }

    private static ScanLog sl(ScanLog s) { return s; }

    @Test
    void scanZaehler_heuteUndSiebenTage() {
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        when(scanLogRepo.countByCardIdAndScannedAtAfter(eq(card.getId()), any())).thenReturn(3L, 21L);
        Map<String, Object> z = dienst.scanZaehler(card);
        assertThat(z.get("scansToday")).isEqualTo(3L);
        assertThat(z.get("scans7d")).isEqualTo(21L);
    }

    @Test
    void aktivitaet_geloeschterKunde_leererName() {
        ScanLog sl = scan(0, 1, 0);
        when(sl.getCustomerId()).thenReturn("CUST-WEG");
        when(scanLogRepo.findTop15ByShopIdOrderByScannedAtDesc("SHOP-1")).thenReturn(List.of(sl));
        when(customerRepo.findAllById(any())).thenReturn(List.of());
        when(cardRepo.findAllById(any())).thenReturn(List.of());
        when(ccRepo.findTop5ByCard_ShopOrderByCreatedAtDesc(shop)).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scans = (List<Map<String, Object>>) dienst.aktivitaet(shop).get("scans");
        assertThat(scans.get(0).get("kunde")).isEqualTo("");
        assertThat(scans.get(0).get("karte")).isEqualTo("");
    }
}
