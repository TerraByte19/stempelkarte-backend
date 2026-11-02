package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.KampagneEmpfaenger;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.Zielgruppe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Kampagnen-Abfragen gegen eine echte Datenbank. Der Service-Test mockt
 * die Repositories weg - ob die JPQL laeuft und wen sie liefert, zeigt nur
 * das hier.
 */
@DataJpaTest
class KampagnenAbfragenTest {

    @Autowired private ShopRepository shops;
    @Autowired private CardRepository cards;
    @Autowired private CustomerRepository customers;
    @Autowired private CustomerCardRepository customerCards;
    @Autowired private KampagneRepository kampagnen;
    @Autowired private KampagneEmpfaengerRepository empfaenger;

    private final Instant jetzt = Instant.now();
    private Shop shop;
    private Card card;

    private CustomerCard kundenkarte(String email) {
        if (shop == null) {
            shop = shops.save(Shop.create("laden@localhost.test", "hash", "Testladen", 5));
            card = cards.save(Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee"));
        }
        Customer c = customers.save(Customer.create("Kunde", email));
        return customerCards.save(CustomerCard.create(c, card));
    }

    @Test
    void basis_ohneGesperrte_mitAltenZeilen() {
        CustomerCard alt = kundenkarte("alt@test.de");
        CustomerCard versorgt = kundenkarte("versorgt@test.de");
        versorgt.kampagneErhalten("vorige", jetzt);
        customerCards.save(versorgt);
        CustomerCard gesperrt = kundenkarte("gesperrt@test.de");
        gesperrt.willkommenVormerken("Erster Kaffee geht auf uns");
        customerCards.save(gesperrt);

        assertThat(customerCards.findKampagnenBasis(shop)).extracting(CustomerCard::getId)
                .containsExactlyInAnyOrder(alt.getId(), versorgt.getId());
    }

    @Test
    void basis_ohneInaktiveKarte() {
        kundenkarte("a@test.de");
        card.setActive(false);
        cards.save(card);
        assertThat(customerCards.findKampagnenBasis(shop)).isEmpty();
    }

    @Test
    void empfaengerZeilen_offeneSeite_zaehler_loeschen() {
        CustomerCard a = kundenkarte("a@test.de");
        CustomerCard b = kundenkarte("b@test.de");
        Kampagne k = kampagnen.save(Kampagne.starte(shop, "Heute 20%", Zielgruppe.ALLE, 2, jetzt));
        KampagneEmpfaenger za = empfaenger.save(KampagneEmpfaenger.neu(k.getId(), a.getId()));
        empfaenger.save(KampagneEmpfaenger.neu(k.getId(), b.getId()));

        assertThat(empfaenger.findByKampagneIdAndErledigtFalse(k.getId(), PageRequest.of(0, 1))).hasSize(1);
        assertThat(empfaenger.countByKampagneIdAndErledigtFalse(k.getId())).isEqualTo(2);

        za.erledigen();
        empfaenger.save(za);
        assertThat(empfaenger.countByKampagneIdAndErledigtFalse(k.getId())).isEqualTo(1);
        assertThat(empfaenger.findByKampagneIdAndErledigtFalse(k.getId(), PageRequest.of(0, 25)))
                .extracting(KampagneEmpfaenger::getCustomerCardId).containsExactly(b.getId());

        empfaenger.deleteByKampagneId(k.getId());
        assertThat(empfaenger.countByKampagneIdAndErledigtFalse(k.getId())).isZero();
    }

    @Test
    void kampagnenVerlauf_laufend_undLoeschen() {
        kundenkarte("a@test.de");
        Kampagne k = kampagnen.save(Kampagne.starte(shop, "Heute 20%", Zielgruppe.INAKTIV_30, 1, jetzt));

        assertThat(kampagnen.findByStatus(Kampagne.LAEUFT)).extracting(Kampagne::getId)
                .containsExactly(k.getId());
        assertThat(kampagnen.findByShop(shop)).hasSize(1);

        k.beenden(jetzt.plusSeconds(60));
        kampagnen.save(k);
        assertThat(kampagnen.findByStatus(Kampagne.LAEUFT)).isEmpty();
        assertThat(kampagnen.findTop20ByShopOrderByErstelltAmDesc(shop))
                .extracting(Kampagne::getGruppe).containsExactly("INAKTIV_30");

        kampagnen.deleteByShop(shop);
        assertThat(kampagnen.findByShop(shop)).isEmpty();
    }

    @Test
    void ergebnis_kundenIdsUndFaelligeFertige() {
        CustomerCard a = kundenkarte("a@test.de");
        CustomerCard b = kundenkarte("b@test.de");
        Kampagne k = kampagnen.save(Kampagne.starte(shop, "x", Zielgruppe.ALLE, 2, jetzt.minus(Duration.ofDays(9))));
        empfaenger.save(KampagneEmpfaenger.neu(k.getId(), a.getId()));
        empfaenger.save(KampagneEmpfaenger.neu(k.getId(), b.getId()));

        assertThat(empfaenger.findByKampagneId(k.getId())).hasSize(2);
        List<Object[]> paare = customerCards.kundenIdsVon(List.of(a.getId(), b.getId()));
        assertThat(paare).hasSize(2);
        assertThat(paare).extracting(p -> p[1]).containsExactlyInAnyOrder(
                a.getCustomer().getId(), b.getCustomer().getId());

        k.beenden(jetzt.minus(Duration.ofDays(8)));
        kampagnen.save(k);
        assertThat(kampagnen.findByStatusAndErgebnisAmIsNullAndFertigAmBefore(
                Kampagne.FERTIG, jetzt.minus(Duration.ofDays(7)))).extracting(Kampagne::getId).containsExactly(k.getId());
        k.ergebnis(1, jetzt);
        kampagnen.save(k);
        assertThat(kampagnen.findByStatusAndErgebnisAmIsNullAndFertigAmBefore(
                Kampagne.FERTIG, jetzt.minus(Duration.ofDays(7)))).isEmpty();
    }

    @Test
    void geplante_werdenFaellig() {
        kundenkarte("a@test.de");
        Kampagne bald = kampagnen.save(Kampagne.starte(shop, "bald", Zielgruppe.ALLE, 1, jetzt, jetzt.plusSeconds(60)));
        Kampagne spaeter = kampagnen.save(Kampagne.starte(shop, "spaeter", Zielgruppe.ALLE, 1, jetzt, jetzt.plusSeconds(3600)));

        assertThat(kampagnen.findByStatus(Kampagne.LAEUFT)).isEmpty();
        assertThat(kampagnen.findByStatusAndGeplantAmLessThanEqual(Kampagne.GEPLANT, jetzt)).isEmpty();
        assertThat(kampagnen.findByStatusAndGeplantAmLessThanEqual(Kampagne.GEPLANT, jetzt.plusSeconds(120)))
                .extracting(Kampagne::getId).containsExactly(bald.getId());
        assertThat(kampagnen.findByIdAndShop(spaeter.getId(), shop)).isPresent();
    }
}
