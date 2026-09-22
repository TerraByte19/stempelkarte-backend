package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Laesst processScan WIRKLICH laufen - mit echtem CustomerService und nur
 * gemockten Repositories.
 *
 * Diese Luecke ist beim Bau der Punktekarte aufgefallen: ScanControllerTest
 * mockt den CustomerService weg und prueft nur den Controller. Der
 * Stempel-Weg selbst, der in echten Laeden laeuft, hatte damit keinen Test -
 * ausgerechnet die Methode, in der fuer die Punktekarte der QR-Parse-Block
 * durch QrPayload.parse ersetzt wurde.
 *
 * Festgenagelt wird deshalb das Verhalten, auf das sich der Scanner
 * verlaesst: hochzaehlen, voll werden, einloesen, mehrere Stempel in einem
 * Scan, und die Fehlermeldungen, die das Personal im Scanner zu sehen
 * bekommt.
 */
class StempelScanTest {

    private CustomerRepository customerRepo;
    private CardRepository cardRepo;
    private CustomerCardRepository customerCardRepo;
    private ScanLogRepository scanLogRepo;
    private CustomerService service;

    private Shop shop;
    private Card card;
    private CustomerCard cc;

    private static final String QR = "{\"cid\":\"CUST-1\",\"cardId\":\"CARD-1\"}";

    @BeforeEach
    void aufbau() {
        customerRepo = mock(CustomerRepository.class);
        cardRepo = mock(CardRepository.class);
        customerCardRepo = mock(CustomerCardRepository.class);
        scanLogRepo = mock(ScanLogRepository.class);

        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");

        card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");

        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn("CUST-1");
        cc = CustomerCard.create(customer, card);

        when(customerRepo.findById("CUST-1")).thenReturn(Optional.of(customer));
        when(cardRepo.findById("CARD-1")).thenReturn(Optional.of(card));
        when(customerCardRepo.findByCustomerAndCard(customer, card))
                .thenReturn(Optional.of(cc));
        when(customerCardRepo.save(any(CustomerCard.class))).thenAnswer(i -> i.getArgument(0));

        service = new CustomerService(customerRepo, cardRepo, customerCardRepo,
                mock(AppleDeviceRepository.class), mock(EmailService.class), scanLogRepo);
    }

    @Test
    void einStempelZaehltHoch() {
        ScanResult r = service.processScan(QR, shop, 1);

        assertThat(cc.getStamps()).isEqualTo(1);
        assertThat(r).isInstanceOf(ScanResult.Stamped.class);
        assertThat(r.message()).isEqualTo("Stempel hinzugefuegt (1/10)");
        assertThat(r.rewardsEarnedThisScan()).isZero();
    }

    @Test
    void zehnterStempelMachtDieKarteVoll() {
        service.processScan(QR, shop, 9);
        ScanResult r = service.processScan(QR, shop, 1);

        assertThat(cc.getStamps()).isEqualTo(10);
        assertThat(r).isInstanceOf(ScanResult.Full.class);
        assertThat(r.message()).contains("Karte voll!");
        assertThat(r.rewardsEarnedThisScan()).isEqualTo(1);
    }

    @Test
    void naechsterScanAufVollerKarteLoestEin() {
        service.processScan(QR, shop, 10);
        ScanResult r = service.processScan(QR, shop, 1);

        assertThat(r).isInstanceOf(ScanResult.Redeemed.class);
        assertThat(r.message()).contains("eingeloest");
        assertThat(cc.getStamps()).isZero();
        assertThat(cc.getTotalRewards()).isEqualTo(1);
    }

    @Test
    void mehrereStempelInEinemScan_loesenUnterwegsAutomatischEin() {
        // Der Ueberzieh-Fall, und er ist ueberraschender als er aussieht:
        // 8 Stempel drauf, dann 4 auf einmal.
        //
        //   Stempel 1 -> 9/10
        //   Stempel 2 -> 10/10, Karte voll, rewardsEarnedThisScan = 1
        //   Stempel 3 -> die volle Karte wird EINGELOEST, Stand faellt auf 0
        //   Stempel 4 -> 1/10 auf der neuen Karte
        //
        // Endstand ist also 1, nicht 2: ein Stempel der vier ist in die
        // Einloesung geflossen. Die Belohnung ist damit verbraucht, ohne dass
        // jemand danach gefragt hat. Fuer den Scanner ist das gewollt - er
        // zeigt "eingeloest" an -, aber wer count grosszuegig setzt, sollte
        // wissen, dass er damit einloest.
        service.processScan(QR, shop, 8);
        ScanResult r = service.processScan(QR, shop, 4);

        assertThat(r.rewardsEarnedThisScan()).isEqualTo(1);
        assertThat(cc.getStamps()).isEqualTo(1);
        assertThat(cc.getTotalRewards()).isEqualTo(1);
    }

    @Test
    void schreibtEinenScanLogMitDerAnzahl() {
        service.processScan(QR, shop, 3);

        ArgumentCaptor<ScanLog> captor = ArgumentCaptor.forClass(ScanLog.class);
        verify(scanLogRepo, atLeastOnce()).save(captor.capture());
        ScanLog log = captor.getValue();
        assertThat(log.getStampsAdded()).isEqualTo(3);
        assertThat(log.getShopId()).isEqualTo("SHOP-1");
        assertThat(log.getCardId()).isEqualTo("CARD-1");
    }

    @Test
    void scheiterndesProtokollBrichtDenScanNichtAb() {
        // Der Stempel ist gesetzt, bevor der ScanLog geschrieben wird. Wuerde
        // ein Protokollfehler durchschlagen, meldete der Scanner einen Fehler
        // fuer etwas, das stattgefunden hat - und das Personal stempelt
        // nochmal.
        when(scanLogRepo.save(any(ScanLog.class)))
                .thenThrow(new RuntimeException("Datenbank weg"));

        ScanResult r = service.processScan(QR, shop, 1);

        assertThat(r).isInstanceOf(ScanResult.Stamped.class);
        assertThat(cc.getStamps()).isEqualTo(1);
    }

    @Test
    void unleserlicherQr_meldetUngueltigenQrCode() {
        // Diese Meldung sieht das Personal im Scanner. Sie ist beim Umbau auf
        // QrPayload wortgleich uebernommen worden, und genau das haelt dieser
        // Test fest.
        assertThatThrownBy(() -> service.processScan("kein json", shop, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ungueltiger QR-Code");
    }

    @Test
    void fremdeKarte_wirdAbgelehnt() {
        Shop andererLaden = mock(Shop.class);
        when(andererLaden.getId()).thenReturn("SHOP-2");

        assertThatThrownBy(() -> service.processScan(QR, andererLaden, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gehoert nicht zu deinem Shop");
    }

    @Test
    void deaktivierteKarte_wirdAbgelehnt() {
        card.setActive(false);

        assertThatThrownBy(() -> service.processScan(QR, shop, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht mehr aktiv");
    }

    @Test
    void resetSetztStempelUndBelohnungenAufNull() {
        service.processScan(QR, shop, 10);
        service.processScan(QR, shop, 1);   // einloesen -> totalRewards = 1

        CustomerCard zurueckgesetzt = service.resetCard(QR, shop);

        assertThat(zurueckgesetzt.getStamps()).isZero();
        assertThat(zurueckgesetzt.getTotalRewards()).isZero();
    }

    @Test
    void punktekarteAmStempelWeg_verhaeltSichWieBisher() {
        // Der Stempel-Weg hat keine Typpruefung und soll auch keine bekommen:
        // er ist der Pfad, der in echten Laeden laeuft, und jede zusaetzliche
        // Abzweigung dort ist ein Risiko. Eine Punktekarte hat threshold 1,
        // wird also sofort voll - unschoen, aber harmlos, und der Scanner
        // fragt ohnehin vorher /api/scan/state nach dem Typ.
        Card punktekarte = Card.createPoints(shop, "Bistro", "Punkte",
                100, com.example.stemplekarte.model.PointsRounding.GENAU);
        when(cardRepo.findById("CARD-1")).thenReturn(Optional.of(punktekarte));
        Customer kunde = mock(Customer.class);
        when(kunde.getId()).thenReturn("CUST-1");
        when(customerRepo.findById("CUST-1")).thenReturn(Optional.of(kunde));
        CustomerCard pk = CustomerCard.create(kunde, punktekarte);
        when(customerCardRepo.findByCustomerAndCard(kunde, punktekarte))
                .thenReturn(Optional.of(pk));

        ScanResult r = service.processScan(QR, shop, 1);

        assertThat(r).isInstanceOf(ScanResult.Full.class);
        assertThat(pk.getPointsX100()).isZero();   // Punkte bleiben unberuehrt
    }
}
