package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.example.stemplekarte.repository.SentNewsletterRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.service.EmailService;
import com.example.stemplekarte.service.ShopService;
import com.example.stemplekarte.service.StatsService;
import com.example.stemplekarte.wallet.CloudinaryService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Empfaenger wurden ueber customer_card geholt. Wer zwei Karten desselben
 * Ladens hatte, bekam denselben Newsletter zweimal, und der Knopf zeigte
 * eine zu hohe Empfaengerzahl an.
 *
 * Dasselbe beim Abmelden: der Link widerrief die Einwilligung nur fuer die
 * eine Karte, obwohl die Seite "keine Angebote mehr von <Laden>" versprach.
 */
class NewsletterEmpfaengerTest {

    private final CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
    private final EmailService mailer = mock(EmailService.class);
    private final SentNewsletterRepository verlaufRepo = mock(SentNewsletterRepository.class);

    private Shop laden;
    private Customer gast;
    private CustomerCard karteA;
    private CustomerCard karteB;

    private void aufbau() {
        laden = Shop.create("laden@test.de", "hash", "Test-Laden", 3);
        Card kaffee = Card.create(laden, "Kaffee", "Beschreibung", 10, "Gratis Kaffee");
        Card mittag = Card.create(laden, "Mittag", "Beschreibung", 8, "Gratis Suppe");
        gast = Customer.create("Alex", "alex@test.de");
        gast.confirmEmail();
        karteA = CustomerCard.create(gast, kaffee);
        karteB = CustomerCard.create(gast, mittag);
        karteA.giveMarketingConsent();
        karteB.giveMarketingConsent();
        when(ccRepo.findByCard_ShopAndMarketingConsentTrue(laden)).thenReturn(List.of(karteA, karteB));
    }

    private ShopController controller() {
        return new ShopController(mock(ShopService.class), mock(CardService.class), ccRepo,
                mock(CloudinaryService.class), mailer, verlaufRepo, mock(StatsService.class));
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(
                new JwtAuthFilter.ShopPrincipal(laden), null, List.of());
    }

    @Test
    void zweiKartenEinKunde_zaehltAlsEinEmpfaenger() {
        aufbau();
        Map<String, Object> zahlen = controller().newsletterRecipients(auth());
        assertThat(zahlen.get("total")).isEqualTo(1);
        assertThat(zahlen.get("confirmed")).isEqualTo(1L);
    }

    @Test
    void zweiKartenEinKunde_bekommtNurEineMail() {
        aufbau();
        var req = new ShopController.NewsletterRequest("Betreff", "Text", List.of());
        Map<String, Object> ergebnis = controller().sendNewsletter(req, auth());

        assertThat(ergebnis.get("sent")).isEqualTo(1);
        verify(mailer, times(1)).sendNewsletterMail(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void abmelden_giltFuerAlleKartenDesLadens() {
        aufbau();
        when(ccRepo.findById(karteA.getId())).thenReturn(java.util.Optional.of(karteA));
        when(ccRepo.findByCustomer(gast)).thenReturn(List.of(karteA, karteB));

        new PublicEmailController(mock(CustomerRepository.class), ccRepo,
                mock(CustomerService.class), mock(EmailService.class))
                .unsubscribe(karteA.getId(), karteA.getAuthToken());

        assertThat(karteA.isMarketingConsent()).isFalse();
        assertThat(karteB.isMarketingConsent()).isFalse();
    }
}
