package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Alte Bewertungslinks (/bewertung/...) stehen noch auf Wallet-Karten. Die
 * private Rueckmeldung gibt es nicht mehr - die Seite fuehrt nur noch zu
 * Google und zurueck zur Karte, ohne Sterne-Formular.
 */
class BewertungControllerTest {

    private CustomerCardRepository ccRepo;
    private BewertungController controller;
    private Shop shop;

    @BeforeEach
    void aufbau() {
        ccRepo = mock(CustomerCardRepository.class);
        controller = new BewertungController(ccRepo);
        shop = Shop.create("a@b.de", "hash", "Café <b>Mokka</b>", 3);
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        CustomerCard cc = CustomerCard.create(Customer.create("Adham <script>", "a@test.de"), card);
        when(ccRepo.findByCustomer_IdAndCard_Id("CUST-1", "CARD-1")).thenReturn(Optional.of(cc));
    }

    @Test
    void seite_unbekannteKarte() {
        String html = controller.seite("CUST-X", "CARD-1").getBody();
        assertThat(html).contains("Karte nicht gefunden").doesNotContain("stern");
    }

    @Test
    void seite_mitGoogleLink_nurGoogleUndZurueck_keinFormular() {
        shop.updateGoogleReviewUrl("https://g.page/r/abc");
        String html = controller.seite("CUST-1", "CARD-1").getBody();
        assertThat(html).contains("Bewerte uns bei Google")
                .contains("g.page/r/abc")
                .contains("href='/karte/CUST-1/CARD-1'")
                .doesNotContain("class='stern'")
                .doesNotContain("/api/public/rueckmeldung");
    }

    @Test
    void seite_ohneGoogleLink_nurZurueck() {
        String html = controller.seite("CUST-1", "CARD-1").getBody();
        assertThat(html).doesNotContain("Bewerte uns bei Google")
                .doesNotContain("class='stern'")
                .contains("href='/karte/CUST-1/CARD-1'");
    }
}
