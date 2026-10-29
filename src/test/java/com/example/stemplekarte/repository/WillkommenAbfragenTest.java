package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die drei Abfragen des Willkommensgeschenks gegen eine echte Datenbank.
 * Die Service-Tests mocken das Repository weg - ob die JPQL ueberhaupt
 * laeuft, zeigt nur dieser Test.
 *
 * - findZumEinloesen: sperrt die Zeile und laedt keine Beziehungen mit. Sind
 *   Karte und Kunde vorher in derselben Sitzung geladen, zeigen die
 *   Beziehungen auf genau diese Objekte - der Controller liest sie spaeter
 *   ohne Sitzung (open-in-view ist aus).
 * - Rueckhol-Kandidaten: gesperrte Karten tauchen nicht auf, alte Zeilen mit
 *   NULL in der neuen Spalte schon.
 * - Zaehler fuer den Karten-Editor.
 */
@DataJpaTest
class WillkommenAbfragenTest {

    @Autowired private ShopRepository shops;
    @Autowired private CardRepository cards;
    @Autowired private CustomerRepository customers;
    @Autowired private CustomerCardRepository customerCards;
    @Autowired private EntityManager em;

    private Shop shop;
    private Card card;

    private CustomerCard kundenkarte(String email, boolean gesperrt) {
        if (shop == null) {
            shop = shops.save(Shop.create("laden@localhost.test", "hash", "Testladen", 5));
            card = cards.save(Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee"));
        }
        Customer c = customers.save(Customer.create("Kunde", email));
        CustomerCard cc = CustomerCard.create(c, card);
        if (gesperrt) cc.willkommenVormerken("Erster Kaffee geht auf uns");
        return customerCards.save(cc);
    }

    @Test
    void zumEinloesen_findetDieKarte_undBeziehungenSindGeladen() {
        CustomerCard cc = kundenkarte("a@test.de", true);
        String cid = cc.getCustomer().getId();
        em.flush();
        em.clear();

        // Wie im Service: erst Karte und Kunde, dann die gesperrte Zeile.
        cards.findById(card.getId()).orElseThrow();
        customers.findById(cid).orElseThrow();
        CustomerCard geladen = customerCards.findZumEinloesen(cid, card.getId()).orElseThrow();

        assertThat(geladen.isWillkommenOffen()).isTrue();
        assertThat(Hibernate.isInitialized(geladen.getCard())).isTrue();
        assertThat(Hibernate.isInitialized(geladen.getCustomer())).isTrue();

        geladen.willkommenEinloesen(Instant.now());
        customerCards.save(geladen);
        em.flush();
        em.clear();

        CustomerCard danach = customerCards.findById(geladen.getId()).orElseThrow();
        assertThat(danach.isWillkommenOffen()).isFalse();
        assertThat(danach.getWillkommenEingeloestAt()).isNotNull();
    }

    @Test
    void zumEinloesen_fremdeKarte_leer() {
        CustomerCard cc = kundenkarte("b@test.de", true);
        assertThat(customerCards.findZumEinloesen(cc.getCustomer().getId(), "CARD-GIBTSNICHT")).isEmpty();
    }

    @Test
    void rueckhol_ohneGesperrte_mitAltenZeilen() {
        CustomerCard alt = kundenkarte("alt@test.de", false);
        CustomerCard gesperrt = kundenkarte("neu@test.de", true);
        em.flush();
        // Eine Zeile von vor der Spalte hat NULL, nicht false.
        em.createNativeQuery("update customer_cards set welcome_pending = null where id = ?1")
                .setParameter(1, alt.getId()).executeUpdate();
        em.clear();

        Instant zukunft = Instant.now().plusSeconds(3600);
        List<CustomerCard> kandidaten = customerCards.findRueckholKandidaten(
                shop, zukunft, PageRequest.of(0, 50));

        assertThat(kandidaten).extracting(CustomerCard::getId)
                .contains(alt.getId())
                .doesNotContain(gesperrt.getId());
    }

    @Test
    void zaehler_offenUndEingeloest() {
        kundenkarte("c1@test.de", true);
        kundenkarte("c2@test.de", true);
        CustomerCard eingeloest = kundenkarte("c3@test.de", true);
        eingeloest.willkommenEinloesen(Instant.now());
        customerCards.save(eingeloest);
        kundenkarte("c4@test.de", false);
        em.flush();

        assertThat(customerCards.countByCardAndWelcomePendingTrue(card)).isEqualTo(2);
        assertThat(customerCards.countByCardAndWelcomeRedeemedAtIsNotNull(card)).isEqualTo(1);
    }
}
