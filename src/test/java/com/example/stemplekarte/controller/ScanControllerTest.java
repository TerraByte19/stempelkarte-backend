package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.security.StaffTokenFilter;
import com.example.stemplekarte.service.CardEventHub;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.wallet.ApnsPushService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import com.example.stemplekarte.wallet.WalletNotifier;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Der Shop kommt als Lazy-Proxy aus dem StaffTokenFilter. Wird im Controller
 * ein Feld davon angefasst, das der Proxy nicht selbst kennt, laedt Hibernate
 * nach - und die Session ist dort schon geschlossen.
 *
 * Genau das ist am 12.09.2026 passiert: eine Protokollzeile las shop.getName(),
 * jeder Scan endete in einer LazyInitializationException, und der Scanner
 * meldete pauschal "Ungueltiger QR-Code". Der Stempel war zu dem Zeitpunkt
 * bereits gesetzt - der Fehler war also nicht nur kosmetisch, sondern hat
 * doppelte Stempel provoziert.
 */
class ScanControllerTest {

    private ScanController controllerMitShop(Shop shop) {
        CustomerService service = mock(CustomerService.class);

        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn("CUST-TEST");

        Card card = mock(Card.class);
        when(card.getId()).thenReturn("CARD-TEST");
        when(card.getRewardThreshold()).thenReturn(10);

        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getId()).thenReturn("CC-TEST");
        when(cc.getCustomer()).thenReturn(customer);
        when(cc.getCard()).thenReturn(card);
        when(cc.getStamps()).thenReturn(1);

        when(service.processScan(anyString(), any(Shop.class), anyInt()))
                .thenReturn(new ScanResult.Stamped(cc, "Stempel hinzugefuegt", 0));

        return new ScanController(service, new WalletNotifier(
                mock(CardEventHub.class), mock(ApnsPushService.class),
                mock(GoogleWalletService.class)));
    }

    private Authentication authFuer(Shop shop) {
        StaffToken staff = mock(StaffToken.class);
        when(staff.getShop()).thenReturn(shop);
        return new UsernamePasswordAuthenticationToken(
                new StaffTokenFilter.StaffPrincipal(staff), null, List.of());
    }

    @Test
    void scanLaeuftDurch_wennNurDieShopIdVerfuegbarIst() {
        // Proxy-Verhalten: die ID kennt er, alles andere wuerde er nachladen.
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-TEST");
        when(shop.getName()).thenThrow(new LazyInitializationException(
                "Could not initialize proxy [Shop#SHOP-TEST] - no session"));

        ScanController controller = controllerMitShop(shop);
        var req = new ScanController.ScanRequest("{\"cid\":\"CUST-TEST\",\"cardId\":\"CARD-TEST\"}", 1);

        var antwort = assertDoesNotThrow(() -> controller.scan(req, authFuer(shop)));

        assertEquals("stamped", antwort.action());
        assertEquals("CARD-TEST", antwort.cardId());
        assertEquals(1, antwort.stamps());
    }
}
