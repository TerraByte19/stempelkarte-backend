package com.example.stemplekarte.service;

import com.example.stemplekarte.controller.LandingController;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Optionale Anmeldefelder: was der Laden einstellt, was das Formular zeigt,
 * was der Server annimmt. Der Geburtstag kommt aus einem date-Feld (ISO),
 * die Nummer tippt der Kunde frei - beides landet in der Kundenliste.
 */
class AnmeldedatenTest {

    @Test
    void geburtstag_isoDatum_sonstFehler() {
        assertThat(Anmeldedaten.geburtstag(null)).isNull();
        assertThat(Anmeldedaten.geburtstag("  ")).isNull();
        assertThat(Anmeldedaten.geburtstag("1990-12-24")).isEqualTo(LocalDate.of(1990, 12, 24));
        assertThatThrownBy(() -> Anmeldedaten.geburtstag("24.12.1990"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Anmeldedaten.geburtstag(LocalDate.now().plusDays(1).toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Anmeldedaten.geburtstag("1850-01-01"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void telefon_nurTelefonzeichen() {
        assertThat(Anmeldedaten.telefon(null)).isNull();
        assertThat(Anmeldedaten.telefon("  ")).isNull();
        assertThat(Anmeldedaten.telefon(" +49 170 123 45 67 ")).isEqualTo("+49 170 123 45 67");
        assertThat(Anmeldedaten.telefon("0170/1234567")).isEqualTo("0170/1234567");
        assertThatThrownBy(() -> Anmeldedaten.telefon("ruf mich an"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Anmeldedaten.telefon("+++"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Anmeldedaten.telefon("1".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void karte_fuelltNurLeereFelder() {
        // An der Karte des Ladens, nicht am Kunden. Vorhandenes bleibt, auch
        // wenn jemand ueber das offene Formular etwas anderes schickt.
        Shop shop = Shop.create("a@b.de", "hash", "Laden", 3);
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        CustomerCard cc = CustomerCard.create(Customer.create("Adham", "a@b.de"), card);
        cc.anmeldedatenErgaenzen(LocalDate.of(1990, 12, 24), null);
        cc.anmeldedatenErgaenzen(LocalDate.of(2000, 1, 1), "0170");
        assertThat(cc.getBirthday()).isEqualTo(LocalDate.of(1990, 12, 24));
        assertThat(cc.getPhone()).isEqualTo("0170");
        cc.anmeldedatenErgaenzen(null, "0171");
        assertThat(cc.getPhone()).isEqualTo("0170");
    }

    @Test
    void laden_einstellungNurOffOptionalRequired() {
        Shop shop = Shop.create("a@b.de", "hash", "Laden", 3);
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.findById("SHOP-1")).thenReturn(Optional.of(shop));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ShopService dienst = new ShopService(repo, null, null, null);

        assertThat(shop.getSignupBirthday()).isEqualTo(Shop.FELD_AUS);
        dienst.updateAnmeldefelder("SHOP-1", "optional", "REQUIRED");
        assertThat(shop.getSignupBirthday()).isEqualTo(Shop.FELD_OPTIONAL);
        assertThat(shop.getSignupPhone()).isEqualTo(Shop.FELD_PFLICHT);
        dienst.updateAnmeldefelder("SHOP-1", "", null);
        assertThat(shop.getSignupBirthday()).isEqualTo(Shop.FELD_AUS);
        assertThat(shop.getSignupPhone()).isEqualTo(Shop.FELD_AUS);
        assertThatThrownBy(() -> dienst.updateAnmeldefelder("SHOP-1", "immer", "OFF"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formular_zeigtNurEingeschalteteFelder() {
        Shop shop = Shop.create("a@b.de", "hash", "Laden", 3);
        assertThat(LandingController.anmeldeFelder(shop)).isEmpty();
        assertThat(LandingController.anmeldeFelder(null)).isEmpty();

        shop.updateAnmeldefelder(Shop.FELD_PFLICHT, Shop.FELD_OPTIONAL);
        String html = LandingController.anmeldeFelder(shop);
        assertThat(html).contains("id='birthday'").contains("data-required='1'")
                .contains("id='phone'").contains("Handynummer (optional)").contains("data-required='0'")
                .doesNotContain("Geburtstag (optional)");
    }
}
