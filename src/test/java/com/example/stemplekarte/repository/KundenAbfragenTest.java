package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.KundenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Kundenliste gegen eine echte Datenbank: Suche ohne Gross/Klein in
 * Name und E-Mail, nur der eigene Laden, nur aktive Karten, zuletzt
 * gesehene zuerst. Und dass die Seitenzaehlung mit dem EntityGraph und
 * dem coalesce in der Sortierung ueberhaupt laeuft.
 */
@DataJpaTest
class KundenAbfragenTest {

    @Autowired private ShopRepository shops;
    @Autowired private CardRepository cards;
    @Autowired private CustomerRepository customers;
    @Autowired private CustomerCardRepository customerCards;

    private Shop laden(String email) {
        return shops.save(Shop.create(email, "hash", "Laden " + email, 5));
    }

    private CustomerCard kundenkarte(Card card, String name, String email) {
        Customer c = customers.save(Customer.create(name, email));
        return customerCards.save(CustomerCard.create(c, card));
    }

    private Page<CustomerCard> suche(Shop shop, String q) {
        return customerCards.findKunden(shop, KundenService.muster(q), PageRequest.of(0, 25));
    }

    @Test
    void sucheInNameUndEmail_ohneGrossKlein_nurEigenerLaden() {
        Shop shop = laden("a@laden.test");
        Card card = cards.save(Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee"));
        CustomerCard adham = kundenkarte(card, "Adham Khalil", "adham@test.de");
        CustomerCard maria = kundenkarte(card, "Maria Müller", "maria@beispiel.de");
        Shop fremd = laden("b@laden.test");
        Card fremdeKarte = cards.save(Card.create(fremd, "Tee", "x", 5, "Tee"));
        kundenkarte(fremdeKarte, "Adham Anderer", "anderer@test.de");

        assertThat(suche(shop, "").getContent()).extracting(CustomerCard::getId)
                .containsExactlyInAnyOrder(adham.getId(), maria.getId());
        assertThat(suche(shop, "ADHAM").getContent()).extracting(CustomerCard::getId)
                .containsExactly(adham.getId());
        assertThat(suche(shop, "beispiel").getContent()).extracting(CustomerCard::getId)
                .containsExactly(maria.getId());
        assertThat(suche(shop, "gibtsnicht").getTotalElements()).isZero();
    }

    @Test
    void inaktiveKarteFehlt_zuletztGeseheneZuerst() throws Exception {
        Shop shop = laden("c@laden.test");
        Card card = cards.save(Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee"));
        CustomerCard alt = kundenkarte(card, "Alt", "alt@test.de");
        Thread.sleep(5);
        CustomerCard neu = kundenkarte(card, "Neu", "neu@test.de");
        Thread.sleep(5);
        alt.addStamp();                       // jetzt zuletzt gesehen
        customerCards.save(alt);
        Card aus = cards.save(Card.create(shop, "Alt-Karte", "x", 5, "x"));
        aus.setActive(false);
        cards.save(aus);
        kundenkarte(aus, "Weg", "weg@test.de");

        Page<CustomerCard> seite = suche(shop, "");
        assertThat(seite.getTotalElements()).isEqualTo(2);
        assertThat(seite.getContent()).extracting(CustomerCard::getId)
                .containsExactly(alt.getId(), neu.getId());
    }
}
