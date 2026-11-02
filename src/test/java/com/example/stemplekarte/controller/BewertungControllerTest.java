package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Rueckmeldung;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.service.RueckmeldungService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Die Bewertungsseite: was der Kunde sieht und wie das Abgeben antwortet.
 * Name und Ladenname kommen von fremden Leuten und landen im HTML -
 * deshalb die Escape-Pruefung.
 */
class BewertungControllerTest {

    private CustomerCardRepository ccRepo;
    private RueckmeldungService service;
    private BewertungController controller;
    private Shop shop;
    private CustomerCard cc;

    @BeforeEach
    void aufbau() {
        ccRepo = mock(CustomerCardRepository.class);
        service = mock(RueckmeldungService.class);
        controller = new BewertungController(ccRepo, service);
        shop = Shop.create("a@b.de", "hash", "Café <b>Mokka</b>", 3);
        shop.setRueckmeldungAktiv(true);
        Card card = Card.create(shop, "Kaffee", "x", 10, "Gratis Kaffee");
        cc = CustomerCard.create(Customer.create("Adham <script>", "a@test.de"), card);
        when(ccRepo.findByCustomer_IdAndCard_Id("CUST-1", "CARD-1")).thenReturn(Optional.of(cc));
    }

    @Test
    void seite_unbekannteKarte() {
        String html = controller.seite("CUST-X", "CARD-1").getBody();
        assertThat(html).contains("Karte nicht gefunden").doesNotContain("stern");
    }

    @Test
    void seite_ladenHatNichtEingeschaltet() {
        shop.setRueckmeldungAktiv(false);
        String html = controller.seite("CUST-1", "CARD-1").getBody();
        assertThat(html).contains("nicht möglich").contains("href='/karte/CUST-1/CARD-1'")
                .doesNotContain("class='stern'");
    }

    @Test
    void seite_fuenfSterne_namenEscaped_googleLink() {
        shop.updateGoogleReviewUrl("https://g.page/r/abc");
        String html = controller.seite("CUST-1", "CARD-1").getBody();
        assertThat(html.split("class='stern'").length - 1).isEqualTo(5);
        assertThat(html).contains("Hallo Adham &lt;script&gt;")
                .contains("Café &lt;b&gt;Mokka&lt;/b&gt;")
                .doesNotContain("<script>Mokka").doesNotContain("Adham <script>")
                .contains("Bewerte uns bei Google")
                .contains("maxlength=\"1000\"")
                .contains("var custId = \"CUST-1\", cardId = \"CARD-1\"")
                .contains("href=\"/karte/CUST-1/CARD-1\"");
    }

    @Test
    void seite_ohneGoogleLink_keinLink() {
        String html = controller.seite("CUST-1", "CARD-1").getBody();
        assertThat(html).doesNotContain("Bewerte uns bei Google");
    }

    private BewertungController.RueckmeldungRequest anfrage(Integer sterne, String website) {
        return new BewertungController.RueckmeldungRequest("CUST-1", "CARD-1", sterne, "Gut", website);
    }

    @Test
    void abgeben_honigtopf_tutSoAlsOb() {
        var antwort = controller.abgeben(anfrage(5, "http://spam"));
        assertThat(antwort.getStatusCode().value()).isEqualTo(200);
        verify(service, never()).abgeben(anyString(), anyString(), anyInt(), any(), any());
    }

    @Test
    void abgeben_fehlendeAngaben() {
        assertThat(controller.abgeben(anfrage(null, null)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.abgeben(new BewertungController.RueckmeldungRequest("", "CARD-1", 3, null, null))
                .getStatusCode().value()).isEqualTo(400);
        verify(service, never()).abgeben(anyString(), anyString(), anyInt(), any(), any());
    }

    @Test
    void abgeben_ok() {
        when(service.abgeben(eq("CUST-1"), eq("CARD-1"), eq(5), eq("Gut"), any()))
                .thenReturn(Rueckmeldung.neu(shop, cc.getId(), 5, "Gut", Instant.now()));
        var antwort = controller.abgeben(anfrage(5, null));
        assertThat(antwort.getStatusCode().value()).isEqualTo(200);
        assertThat(antwort.getBody()).containsEntry("ok", true);
    }

    @Test
    void abgeben_fehlerWerdenZuStatuscodes() {
        when(service.abgeben(anyString(), anyString(), anyInt(), any(), any()))
                .thenThrow(new NoSuchElementException("weg"))
                .thenThrow(new RueckmeldungService.Aus())
                .thenThrow(new RueckmeldungService.HeuteSchon())
                .thenThrow(new IllegalArgumentException("Sterne 1 bis 5"));
        assertThat(controller.abgeben(anfrage(3, null)).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.abgeben(anfrage(3, null)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.abgeben(anfrage(3, null)).getStatusCode().value()).isEqualTo(409);
        var schlecht = controller.abgeben(anfrage(9, null));
        assertThat(schlecht.getStatusCode().value()).isEqualTo(400);
        assertThat(schlecht.getBody()).containsEntry("error", "Sterne 1 bis 5");
    }
}
