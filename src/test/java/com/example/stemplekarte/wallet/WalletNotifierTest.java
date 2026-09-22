package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.service.CardEventHub;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Der Benachrichtigungsblock lag zweimal inline im ScanController und haette
 * mit den Punkte-Endpunkten sechs Kopien ergeben.
 *
 * Die eine Regel, die dabei nicht verlorengehen darf: ein fehlgeschlagener
 * Push bricht den Vorgang NIE ab. Der Stempel beziehungsweise die Buchung
 * ist zu dem Zeitpunkt schon gesetzt - wuerde hier eine Ausnahme
 * durchschlagen, meldete der Scanner einen Fehler fuer etwas, das
 * tatsaechlich stattgefunden hat, und das Personal bucht ein zweites Mal.
 */
class WalletNotifierTest {

    private CustomerCard karte() {
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn("CUST-1");
        Card card = mock(Card.class);
        when(card.getId()).thenReturn("CARD-1");
        when(card.getRewardThreshold()).thenReturn(10);
        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getId()).thenReturn("CC-1");
        when(cc.getCustomer()).thenReturn(customer);
        when(cc.getCard()).thenReturn(card);
        when(cc.getStamps()).thenReturn(3);
        when(cc.getTotalRewards()).thenReturn(0);
        return cc;
    }

    @Test
    void schicktAlleDreiWege() {
        CardEventHub hub = mock(CardEventHub.class);
        ApnsPushService apns = mock(ApnsPushService.class);
        GoogleWalletService google = mock(GoogleWalletService.class);

        new WalletNotifier(hub, apns, google).nachStempelAenderung(karte());

        verify(hub).publishStamps(anyString(), anyInt(), anyInt(), anyInt());
        verify(apns).notifyUpdate("CC-1");
        verify(google).notifyUpdate("CC-1");
    }

    @Test
    void einFehlgeschlagenerPushBrichtNichtAb() {
        CardEventHub hub = mock(CardEventHub.class);
        ApnsPushService apns = mock(ApnsPushService.class);
        GoogleWalletService google = mock(GoogleWalletService.class);

        doThrow(new RuntimeException("APNs weg")).when(apns).notifyUpdate(anyString());

        WalletNotifier notifier = new WalletNotifier(hub, apns, google);

        assertDoesNotThrow(() -> notifier.nachStempelAenderung(karte()));
        // Google wird trotzdem noch versucht - ein toter Weg darf den
        // naechsten nicht mitreissen.
        verify(google).notifyUpdate("CC-1");
    }

    @Test
    void punkteWegSchicktEigenesEreignis() {
        CardEventHub hub = mock(CardEventHub.class);
        ApnsPushService apns = mock(ApnsPushService.class);
        GoogleWalletService google = mock(GoogleWalletService.class);

        new WalletNotifier(hub, apns, google)
                .nachPunkteAenderung("CC-1", 34_000, "Kuchen", 16_000);

        verify(hub).publishPoints("CC-1", 34_000, "Kuchen", 16_000);
        verify(apns).notifyUpdate("CC-1");
        verify(google).notifyUpdate("CC-1");
    }
}
