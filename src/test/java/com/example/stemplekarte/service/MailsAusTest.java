package com.example.stemplekarte.service;

import com.example.stemplekarte.controller.PublicEmailController;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopRepository;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Schalter "Newsletter aus" (Admin, je Laden; das Feld heisst noch
 * mailsAus): der Laden kann keinen Newsletter senden. Alle anderen Mails
 * und die Anmeldung der Kunden laufen wie bei jedem Laden.
 */
class MailsAusTest {

    private JavaMailSender sender;
    private EmailService mailer;
    private Shop aus;
    private Shop an;

    @BeforeEach
    void setUp() {
        sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(a -> new JavaMailSenderImpl().createMimeMessage());
        mailer = new EmailService(sender);
        ReflectionTestUtils.setField(mailer, "enabled", true);
        ReflectionTestUtils.setField(mailer, "from", "info@stampit-app.de");
        ReflectionTestUtils.setField(mailer, "dailyLimit", 300);

        aus = Shop.create("zentrale@kette.de", "hash", "BackBaron", 3);
        aus.setMailsAus(true);
        an = Shop.create("laden@test.de", "hash", "Einzelladen", 3);
    }

    private static EmailService.NewsletterInhalt inhalt() {
        return new EmailService.NewsletterInhalt("Betreff", "Titel", "Text", List.of(), false, null, null);
    }

    private static EmailService.NewsletterEmpfaenger gast() {
        return new EmailService.NewsletterEmpfaenger("gast@test.de", 1, 10, "Kaffee",
                "https://x.test/ab", "https://x.test/weg");
    }

    @Test
    void standard_mailsSindAn() {
        assertThat(an.isMailsAus()).isFalse();
        assertThat(aus.isMailsAus()).isTrue();
        aus.setMailsAus(false);
        assertThat(aus.isMailsAus()).isFalse();
    }

    // ── 1 + 2: Newsletter und Test-Newsletter ───────────────────────────

    @Test
    void newsletter_gehtNichtRaus() {
        assertThat(mailer.sendNewsletterMail(aus, "zentrale@kette.de", inhalt(), gast())).isFalse();
        verify(sender, never()).send(any(MimeMessage.class));

        assertThat(mailer.sendNewsletterMail(an, "laden@test.de", inhalt(), gast())).isTrue();
        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    void newsletterEndpunkte_weisenVorDemVersandAb() {
        assertThatThrownBy(() -> TarifPruefung.pruefeMails(aus))
                .isInstanceOf(TarifPruefung.MailsAus.class);
        TarifPruefung.pruefeMails(an);   // wirft nicht
    }

    // ── Alles andere laeuft normal weiter ───────────────────────────────

    @Test
    void bestaetigungsmail_gehtWeiterRaus() {
        Customer kunde = Customer.create("Ayşe", "ayse@example.com");
        kunde.requireNewConfirmation();

        mailer.sendConfirmationMail(kunde, aus, "CARD-1", null);
        verify(sender).send(any(MimeMessage.class));
    }

    private CustomerService kundenDienst(Card card, EmailService mail, CustomerRepository customerRepo,
                                         CustomerCardRepository ccRepo) {
        CardRepository cardRepo = mock(CardRepository.class);
        when(cardRepo.findById(anyString())).thenReturn(Optional.of(card));
        when(ccRepo.save(any(CustomerCard.class))).thenAnswer(i -> i.getArgument(0));
        when(ccRepo.findByCustomerAndCard(any(), any())).thenReturn(Optional.empty());
        when(customerRepo.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));
        when(customerRepo.findByEmail(anyString())).thenReturn(Optional.empty());
        return new CustomerService(customerRepo, cardRepo, ccRepo, mock(AppleDeviceRepository.class),
                mail, mock(ScanLogRepository.class));
    }

    @Test
    void anmeldungMitEmail_gehtWieBeiJedemLaden() {
        EmailService mail = mock(EmailService.class);
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        Card card = Card.create(aus, "Brotkarte", "x", 10, "1 Brot gratis");

        kundenDienst(card, mail, customerRepo, ccRepo)
                .registerForCard("Ayşe", "ayse@example.com", card.getId(), false);
        verify(ccRepo).save(any(CustomerCard.class));
    }

    @Test
    void karteOhneAnmeldung_haengtNurAmEigenenSchalterDesLadens() {
        EmailService mail = mock(EmailService.class);
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        Card card = Card.create(aus, "Brotkarte", "x", 10, "1 Brot gratis");
        assertThat(aus.isAnmeldungOhneMail()).isFalse();

        assertThatThrownBy(() -> kundenDienst(card, mail, customerRepo, ccRepo).registerOhneMail(card.getId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Anmeldung");
    }

    @Test
    void testphasenErinnerung_gehtAnBeideLaeden() {
        EmailService mail = mock(EmailService.class);
        when(mail.sendTestphaseMail(any(), any(), anyBoolean(), anyString())).thenReturn(true);
        ShopRepository shops = mock(ShopRepository.class);
        // Mittags in Berlin, beide Laeden enden in zwei Tagen.
        Instant mittag = Instant.now().atZone(ZoneId.of("Europe/Berlin"))
                .withHour(12).withMinute(0).toInstant();
        aus.setTestBis(mittag.plus(Duration.ofDays(2)));
        an.setTestBis(mittag.plus(Duration.ofDays(2)));
        when(shops.findByTestBisNotNull()).thenReturn(List.of(aus, an));

        TestphaseErinnerungService dienst = new TestphaseErinnerungService(shops, mail, "https://app.test");
        assertThat(dienst.lauf(mittag)).isEqualTo(2);
        assertThat(aus.getTestMailStufe()).isPositive();
    }

    @Test
    void loeschMail_gehtImmerRaus() {
        Customer kunde = Customer.create("Ayşe", "ayse@example.com");
        Card karteAus = Card.create(aus, "Brotkarte", "x", 10, "Brot");
        EmailService mail = mock(EmailService.class);
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        when(customerRepo.findById(kunde.getId())).thenReturn(Optional.of(kunde));
        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        when(ccRepo.findByCustomer(kunde)).thenReturn(List.of(CustomerCard.create(kunde, karteAus)));

        new PublicEmailController(customerRepo, ccRepo, mock(CustomerService.class), mail)
                .deleteRequest(kunde.getId());
        verify(mail).sendDeletionMail(kunde);
    }

    // ── Was das Dashboard erfaehrt ──────────────────────────────────────

    @Test
    void tarifStand_meldetDemDashboardObMailsAnSind() {
        assertThat(TarifPruefung.stand(aus, 0).funktionen()).containsEntry("mails", false);
        assertThat(TarifPruefung.stand(an, 0).funktionen()).containsEntry("mails", true);
        // Der Tarif selbst aendert sich nicht.
        assertThat(TarifPruefung.stand(aus, 0).funktionen()).containsEntry("newsletter", true);
    }
}
