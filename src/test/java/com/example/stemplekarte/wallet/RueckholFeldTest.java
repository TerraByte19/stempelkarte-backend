package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.RewardService;
import de.brendamour.jpasskit.PKField;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nachrichtenfeld auf der Pass-Rueckseite. iOS meldet nur eine Aenderung an
 * einem Feld mit changeMessage - das Feld muss also bei eingeschalteter
 * Funktion immer da sein, und das changeMessage nur waehrend der Nachricht.
 */
class RueckholFeldTest {

    private PKField feld(boolean aktiv, boolean sichtbar) {
        Shop shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Café Mokka");
        when(shop.isRueckholAktiv()).thenReturn(aktiv);
        Card card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");

        Customer customer = mock(Customer.class);
        when(customer.getName()).thenReturn("Adham");
        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getCustomer()).thenReturn(customer);
        when(cc.getCard()).thenReturn(card);
        when(cc.getStamps()).thenReturn(3);
        when(cc.rueckholSichtbar()).thenReturn(sichtbar);
        when(cc.getWinbackText()).thenReturn("Wir vermissen dich!");

        var pass = new ApplePassService(mock(AppProperties.class),
                mock(PassTemplateGenerator.class), mock(RewardService.class))
                .baueFelder(card, cc, false);
        if (pass.getBackFields() == null || pass.getBackFields().isEmpty()) return null;
        return pass.getBackFields().stream()
                .filter(f -> "nachricht".equals(f.getKey())).findFirst().orElse(null);
    }

    @Test
    void funktionAus_passBleibtWieEr() {
        assertThat(feld(false, false)).isNull();
    }

    @Test
    void ohneNachricht_ruhetextOhneChangeMessage() {
        PKField f = feld(true, false);
        assertThat(f).isNotNull();
        assertThat(f.getValue()).isEqualTo("Hier erscheinen Nachrichten von Café Mokka.");
        assertThat(f.getChangeMessage()).isNull();
    }

    @Test
    void mitNachricht_textUndChangeMessage() {
        PKField f = feld(true, true);
        assertThat(f.getValue()).isEqualTo("Wir vermissen dich!");
        assertThat(f.getChangeMessage()).isEqualTo("%@");
    }
}
