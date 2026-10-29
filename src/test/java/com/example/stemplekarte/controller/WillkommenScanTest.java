package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.GeoVorfallRepository;
import com.example.stemplekarte.repository.PointsBookingRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.security.StaffTokenFilter;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.service.GeoPruefung;
import com.example.stemplekarte.service.PointsService;
import com.example.stemplekarte.service.RewardService;
import com.example.stemplekarte.wallet.WalletNotifier;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /api/scan/willkommen und der Scanner-Zustand dazu.
 *
 * Festgenagelt wird, dass das Einloesen dieselben Huerden hat wie ein
 * Stempel (Token, Standort) - ein Gratis-Artikel von zu Hause waere teurer
 * als ein Stempel - und dass der Controller nach dem Einloesen nur IDs des
 * Ladens anfasst (der Laden ist ein Lazy-Proxy, siehe ScanControllerTest).
 */
class WillkommenScanTest {

    private static final String QR = "{\"cid\":\"CUST-1\",\"cardId\":\"CARD-1\"}";

    private CustomerService service;
    private WalletNotifier notifier;
    private ScanController controller;
    private Shop shop;

    @BeforeEach
    void aufbau() {
        service = mock(CustomerService.class);
        notifier = mock(WalletNotifier.class);
        controller = new ScanController(service, notifier, mock(PointsService.class),
                new GeoPruefung(mock(GeoVorfallRepository.class)));

        // Proxy-Verhalten: die ID kennt er, der Name wuerde nachladen.
        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.getName()).thenThrow(new LazyInitializationException("no session"));
        when(shop.isGeoBefreit()).thenReturn(true);
    }

    private Authentication authFuer(Shop s) {
        StaffToken staff = mock(StaffToken.class);
        when(staff.getShop()).thenReturn(s);
        when(staff.getLabel()).thenReturn("Kasse 1");
        return new UsernamePasswordAuthenticationToken(
                new StaffTokenFilter.StaffPrincipal(staff), null, List.of());
    }

    private CustomerCard freigeschalteteKarte() {
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn("CUST-1");
        Card card = mock(Card.class);
        when(card.getId()).thenReturn("CARD-1");
        when(card.getRewardThreshold()).thenReturn(10);
        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getId()).thenReturn("CC-1");
        when(cc.getCustomer()).thenReturn(customer);
        when(cc.getCard()).thenReturn(card);
        return cc;
    }

    @Test
    void ohneToken_401() {
        var req = new ScanController.WillkommenRequest(QR, null, null);
        assertThatThrownBy(() -> controller.willkommen(req, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(service, never()).willkommenEinloesen(anyString(), any());
    }

    @Test
    void ausserhalbDesLadens_403_undNichtsEingeloest() {
        when(shop.isGeoBefreit()).thenReturn(false);
        when(shop.getLatitude()).thenReturn(52.52);
        when(shop.getLongitude()).thenReturn(13.405);
        // Bestandsladen vor der Frist-Regel: hat Standort, darf grundsaetzlich scannen.
        var req = new ScanController.WillkommenRequest(QR, 48.137, 11.575); // Muenchen

        assertThatThrownBy(() -> controller.willkommen(req, authFuer(shop)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(service, never()).willkommenEinloesen(anyString(), any());
    }

    @Test
    void erfolg_meldetWelcome_undBenachrichtigt() {
        CustomerCard cc = freigeschalteteKarte();
        when(service.willkommenEinloesen(QR, shop)).thenReturn(cc);

        var antwort = controller.willkommen(
                new ScanController.WillkommenRequest(QR, null, null), authFuer(shop));

        assertThat(antwort.action()).isEqualTo("welcome");
        assertThat(antwort.stamps()).isZero();
        assertThat(antwort.rewardThreshold()).isEqualTo(10);
        verify(notifier).nachFreischaltung(cc);
    }

    // ── Scanner-Zustand ───────────────────────────────────────────────────

    @Test
    void zustand_meldetOffenesGeschenk() {
        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        CardRepository cardRepo = mock(CardRepository.class);
        PointsService points = new PointsService(ccRepo, cardRepo,
                mock(PointsBookingRepository.class), mock(ScanLogRepository.class),
                mock(RewardService.class));

        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        Customer customer = mock(Customer.class);
        when(customer.getName()).thenReturn("Ana");
        CustomerCard cc = CustomerCard.create(customer, card);
        cc.willkommenVormerken("Erster Kaffee geht auf uns");
        when(cardRepo.findById("CARD-1")).thenReturn(Optional.of(card));
        when(ccRepo.findByCustomer_IdAndCard_Id("CUST-1", card.getId())).thenReturn(Optional.of(cc));

        var zustand = points.state(QR, shop);
        assertThat(zustand.willkommenOffen()).isTrue();
        assertThat(zustand.willkommenText()).isEqualTo("Erster Kaffee geht auf uns");

        cc.willkommenEinloesen(java.time.Instant.now());
        var danach = points.state(QR, shop);
        assertThat(danach.willkommenOffen()).isFalse();
        assertThat(danach.willkommenText()).isNull();
    }
}
