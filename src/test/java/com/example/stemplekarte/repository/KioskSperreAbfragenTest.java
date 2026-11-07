package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Sperr-Abfragen des Kiosks gegen eine echte Datenbank. Der Service-Test
 * mockt das Repository weg - ob das native "select id ... for update"
 * ueberhaupt laeuft, zeigt nur dieser Test.
 *
 * Wichtig ist, dass die Abfragen NUR die ID liefern und keine Entity in die
 * Sitzung laden: der KioskService sperrt zuerst und laedt danach, damit alles
 * Geladene den Stand nach dem anderen Tablet zeigt.
 */
@DataJpaTest
class KioskSperreAbfragenTest {

    @Autowired private ShopRepository shops;
    @Autowired private CardRepository cards;
    @Autowired private CustomerRepository customers;
    @Autowired private CustomerCardRepository customerCards;
    @Autowired private EntityManager em;

    @Test
    void sperrenLiefertDieId_undLaedtKeineEntity() {
        Shop shop = shops.save(Shop.create("laden@localhost.test", "hash", "Testladen", 5));
        Card card = cards.save(Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee"));
        Customer kunde = customers.save(Customer.create("Kunde", "k@localhost.test"));
        CustomerCard cc = customerCards.save(CustomerCard.create(kunde, card));
        em.flush();
        em.clear();
        Session session = em.unwrap(Session.class);
        assertThat(session.getStatistics().getEntityCount()).isZero();

        assertThat(customerCards.sperrePerId(cc.getId())).contains(cc.getId());
        assertThat(customerCards.sperrePerKundeUndKarte(kunde.getId(), card.getId())).contains(cc.getId());
        // Nur die ID kam zurueck, die Sitzung ist weiter leer.
        assertThat(session.getStatistics().getEntityCount()).isZero();

        // Unbekannt: nichts zu sperren, kein Fehler.
        assertThat(customerCards.sperrePerId("CC-GIBTESNICHT")).isEmpty();
        assertThat(customerCards.sperrePerKundeUndKarte(kunde.getId(), "CARD-FREMD")).isEmpty();

        // Danach laedt der Kiosk normal - und erst jetzt liegt etwas in der Sitzung.
        assertThat(customerCards.findByCustomer_IdAndCard_Id(kunde.getId(), card.getId())).isPresent();
        assertThat(session.getStatistics().getEntityCount()).isGreaterThan(0);
    }
}
