package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.RewardService;
import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.passes.PKGenericPass;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Das Nachrichtenfeld mit echten Kundenkarten statt Mocks: eine Kampagne
 * steht im Feld und meldet sich, nach dem naechsten Besuch bleibt sie still.
 * Laeden ohne Rueckhol und ohne Kampagne bekommen weiterhin kein Feld - ihre
 * Paesse bleiben Byte fuer Byte wie sie sind.
 */
class KampagnenFeldTest {

    private final Instant jetzt = Instant.now();

    private Shop shop(boolean kampagnen) {
        Shop shop = mock(Shop.class);
        when(shop.getName()).thenReturn("Café Mokka");
        when(shop.hatNachrichtenFeld()).thenReturn(kampagnen);
        return shop;
    }

    private CustomerCard karte(Shop shop) {
        Card card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        Customer customer = mock(Customer.class);
        when(customer.getName()).thenReturn("Adham");
        CustomerCard cc = CustomerCard.create(customer, card);
        cc.addStamp();
        return cc;
    }

    private PKField feld(CustomerCard cc) {
        PKGenericPass pass = new ApplePassService(mock(AppProperties.class),
                mock(PassTemplateGenerator.class), mock(RewardService.class))
                .baueFelder(cc.getCard(), cc, false);
        if (pass.getBackFields() == null) return null;
        return pass.getBackFields().stream()
                .filter(f -> "nachricht".equals(f.getKey())).findFirst().orElse(null);
    }

    @Test
    void ladenOhneAlles_keinFeld() {
        assertThat(feld(karte(shop(false)))).isNull();
    }

    @Test
    void feldOhneNachricht_ruhetext() {
        PKField f = feld(karte(shop(true)));
        assertThat(f).isNotNull();
        assertThat(f.getValue()).isEqualTo("Hier erscheinen Nachrichten von Café Mokka.");
        assertThat(f.getChangeMessage()).isNull();
    }

    @Test
    void frischeKampagne_textUndMitteilung() {
        CustomerCard cc = karte(shop(true));
        cc.kampagneErhalten("Heute 20% auf Kuchen", jetzt.plusSeconds(10));
        PKField f = feld(cc);
        assertThat(f.getValue()).isEqualTo("Heute 20% auf Kuchen");
        assertThat(f.getChangeMessage()).isEqualTo("%@");
    }

    @Test
    void nachDemBesuch_textBleibt_ohneMitteilung() throws Exception {
        CustomerCard cc = karte(shop(true));
        cc.kampagneErhalten("Heute 20% auf Kuchen", Instant.now());
        Thread.sleep(5);
        cc.addStamp();
        PKField f = feld(cc);
        assertThat(f.getValue()).isEqualTo("Heute 20% auf Kuchen");
        assertThat(f.getChangeMessage()).isNull();
    }

    @Test
    void kopfKeyBleibt() {
        CustomerCard cc = karte(shop(true));
        cc.kampagneErhalten("x", jetzt.plusSeconds(10));
        PKGenericPass pass = new ApplePassService(mock(AppProperties.class),
                mock(PassTemplateGenerator.class), mock(RewardService.class))
                .baueFelder(cc.getCard(), cc, false);
        assertThat(pass.getHeaderFields()).extracting(PKField::getKey).containsExactly("stamps-header");
    }
}
