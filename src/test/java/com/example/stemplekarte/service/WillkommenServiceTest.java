package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Laesst Anmeldung, Stempel und Einloesen mit echtem CustomerService laufen,
 * nur die Repositories sind gemockt.
 *
 * Festgenagelt wird:
 * - gesperrt wird nur eine NEUE Kundenkarte bei aktivem Angebot - nie eine
 *   bestehende (das waeren echte Karten in echten Laeden),
 * - auf eine gesperrte Karte geht kein Stempel, und zwar ohne Speichern und
 *   ohne ScanLog,
 * - das Geschenk gibt es einmal, nur im eigenen Laden, nur auf aktiver Karte.
 */
class WillkommenServiceTest {

    private CustomerRepository customerRepo;
    private CardRepository cardRepo;
    private CustomerCardRepository customerCardRepo;
    private ScanLogRepository scanLogRepo;
    private EmailService emailService;
    private CustomerService service;

    private Shop shop;
    private Card card;
    private Customer customer;

    private static final String QR = "{\"cid\":\"CUST-1\",\"cardId\":\"CARD-1\"}";

    @BeforeEach
    void aufbau() {
        customerRepo = mock(CustomerRepository.class);
        cardRepo = mock(CardRepository.class);
        customerCardRepo = mock(CustomerCardRepository.class);
        scanLogRepo = mock(ScanLogRepository.class);
        emailService = mock(EmailService.class);

        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");

        card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");

        customer = mock(Customer.class);
        when(customer.getId()).thenReturn("CUST-1");

        when(customerRepo.findById("CUST-1")).thenReturn(Optional.of(customer));
        when(customerRepo.findByEmail(anyString())).thenReturn(Optional.of(customer));
        when(cardRepo.findById(anyString())).thenReturn(Optional.of(card));
        when(customerCardRepo.save(any(CustomerCard.class))).thenAnswer(i -> i.getArgument(0));

        service = new CustomerService(customerRepo, cardRepo, customerCardRepo,
                mock(AppleDeviceRepository.class), emailService, scanLogRepo);
    }

    private CustomerCard gesperrteKarte() {
        CustomerCard cc = CustomerCard.create(customer, card);
        cc.willkommenVormerken("Erster Kaffee geht auf uns");
        return cc;
    }

    // ── Anmeldung ─────────────────────────────────────────────────────────

    @Test
    void neueAnmeldung_beiAktivemAngebot_istGesperrt() {
        card.updateWillkommen(true, null);
        when(customerCardRepo.findByCustomerAndCard(customer, card)).thenReturn(Optional.empty());

        CustomerCard cc = service.registerForCard("Ana", "ana@test.de", "CARD-1", false);

        assertThat(cc.isWillkommenOffen()).isTrue();
        assertThat(cc.getWillkommenText()).isEqualTo(Card.WILLKOMMEN_STANDARD);
        verify(emailService).sendConfirmationMail(customer, shop, card.getId(), Card.WILLKOMMEN_STANDARD);
    }

    @Test
    void neueAnmeldung_ohneAngebot_istOffenWieBisher() {
        when(customerCardRepo.findByCustomerAndCard(customer, card)).thenReturn(Optional.empty());

        CustomerCard cc = service.registerForCard("Ana", "ana@test.de", "CARD-1", false);

        assertThat(cc.isWillkommenOffen()).isFalse();
        verify(emailService).sendConfirmationMail(eq(customer), eq(shop), eq(card.getId()), isNull());
    }

    @Test
    void bestehendeKarte_wirdNieGesperrt() {
        // Der Fall, auf den es ankommt: Stammkunde meldet sich nach dem
        // Einschalten des Angebots nochmal an. Seine Karte bleibt, wie sie ist.
        card.updateWillkommen(true, null);
        CustomerCard bestand = CustomerCard.create(customer, card);
        bestand.addStamp();
        when(customerCardRepo.findByCustomerAndCard(customer, card)).thenReturn(Optional.of(bestand));

        CustomerCard cc = service.registerForCard("Ana", "ana@test.de", "CARD-1", false);

        assertThat(cc).isSameAs(bestand);
        assertThat(cc.isWillkommenOffen()).isFalse();
        assertThat(cc.getStamps()).isEqualTo(1);
    }

    @Test
    void karteUeberAndereWege_wirdNieGesperrt() {
        // Kartenseite, Pass-Download und Scan legen ueber getOrCreate an.
        card.updateWillkommen(true, null);
        when(customerCardRepo.findByCustomerAndCard(customer, card)).thenReturn(Optional.empty());

        CustomerCard cc = service.getOrCreateCustomerCard("CUST-1", "CARD-1");

        assertThat(cc.isWillkommenOffen()).isFalse();
    }

    // ── Stempel-Sperre ────────────────────────────────────────────────────

    @Test
    void stempelAufGesperrteKarte_wirdAbgelehnt() {
        CustomerCard cc = gesperrteKarte();
        when(customerCardRepo.findByCustomerAndCard(customer, card)).thenReturn(Optional.of(cc));

        assertThatThrownBy(() -> service.processScan(QR, shop, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Willkommensgeschenk");

        assertThat(cc.getStamps()).isZero();
        verify(customerCardRepo, never()).save(cc);
        verify(scanLogRepo, never()).save(any(ScanLog.class));
    }

    @Test
    void nachDemEinloesen_stempeltNormal() {
        CustomerCard cc = gesperrteKarte();
        cc.willkommenEinloesen(java.time.Instant.now());
        when(customerCardRepo.findByCustomerAndCard(customer, card)).thenReturn(Optional.of(cc));

        service.processScan(QR, shop, 1);

        assertThat(cc.getStamps()).isEqualTo(1);
    }

    // ── Einloesen ─────────────────────────────────────────────────────────

    @Test
    void einloesen_schaltetFrei() {
        CustomerCard cc = gesperrteKarte();
        when(customerCardRepo.findZumEinloesen("CUST-1", card.getId())).thenReturn(Optional.of(cc));

        CustomerCard ergebnis = service.willkommenEinloesen(QR, shop);

        assertThat(ergebnis.isWillkommenOffen()).isFalse();
        assertThat(ergebnis.getStamps()).isZero();
        verify(customerCardRepo).save(cc);
        // Kein ScanLog: der zaehlt vergebene Stempel.
        verify(scanLogRepo, never()).save(any(ScanLog.class));
    }

    @Test
    void einloesen_zweimal_wirdAbgelehnt() {
        CustomerCard cc = gesperrteKarte();
        when(customerCardRepo.findZumEinloesen("CUST-1", card.getId())).thenReturn(Optional.of(cc));
        service.willkommenEinloesen(QR, shop);

        assertThatThrownBy(() -> service.willkommenEinloesen(QR, shop))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schon eingelöst");
    }

    @Test
    void einloesen_fremderLaden_wirdAbgelehnt() {
        Shop fremd = mock(Shop.class);
        when(fremd.getId()).thenReturn("SHOP-2");

        assertThatThrownBy(() -> service.willkommenEinloesen(QR, fremd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gehoert nicht zu deinem Shop");
    }

    @Test
    void einloesen_inaktiveKarte_wirdAbgelehnt() {
        card.setActive(false);

        assertThatThrownBy(() -> service.willkommenEinloesen(QR, shop))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht mehr aktiv");
    }

    @Test
    void einloesen_ohneKundenkarte_legtNichtsAn() {
        when(customerCardRepo.findZumEinloesen(anyString(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.willkommenEinloesen(QR, shop))
                .isInstanceOf(NoSuchElementException.class);
        verify(customerCardRepo, never()).save(any(CustomerCard.class));
    }

    @Test
    void zuruecksetzen_laesstDieSperreStehen() {
        CustomerCard cc = gesperrteKarte();
        when(customerCardRepo.findByCustomerAndCard(customer, card)).thenReturn(Optional.of(cc));

        service.resetCard(QR, shop);

        assertThat(cc.isWillkommenOffen()).isTrue();
    }

    // ── Mail ──────────────────────────────────────────────────────────────

    @Test
    void mailBlock_leerOhneGeschenk_sonstMitText() {
        EmailService echt = new EmailService(null);
        assertThat(echt.willkommenBlock(null)).isEmpty();
        assertThat(echt.willkommenBlock("Erster Kaffee <b>gratis</b>!"))
                .contains("Willkommensgeschenk")
                .contains("&lt;b&gt;gratis&lt;/b&gt;! Zeig")
                .doesNotContain("!.");
    }
}
