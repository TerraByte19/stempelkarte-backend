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
 * Schalter "Mails aus" (Admin, je Laden): fuer diesen Laden geht KEINE
 * E-Mail raus, auf keinem der fuenf Wege. Fuer jeden anderen Laden bleibt
 * alles, wie es war - das prueft jeder Test mit.
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

    // ── 3: Bestaetigungsmail bei der Anmeldung ──────────────────────────

    @Test
    void bestaetigungsmail_gehtNichtRaus() {
        Customer kunde = Customer.create("Ayşe", "ayse@example.com");
        kunde.requireNewConfirmation();

        mailer.sendConfirmationMail(kunde, aus, "CARD-1", null);
        verify(sender, never()).send(any(MimeMessage.class));

        mailer.sendConfirmationMail(kunde, an, "CARD-1", null);
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
    void anmeldungMitEmail_wirdAbgelehnt_nichtsGespeichert_keineMail() {
        EmailService mail = mock(EmailService.class);
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        Card card = Card.create(aus, "Brotkarte", "x", 10, "1 Brot gratis");
        CustomerService dienst = kundenDienst(card, mail, customerRepo, ccRepo);

        assertThatThrownBy(() -> dienst.registerForCard("Ayşe", "ayse@example.com", card.getId(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ohne Anmeldung");
        verify(customerRepo, never()).save(any());
        verify(ccRepo, never()).save(any());
        verifyNoInteractions(mail);
    }

    @Test
    void karteOhneAnmeldung_gehtAuchOhneDenSchalterDesLadens() {
        EmailService mail = mock(EmailService.class);
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        Card card = Card.create(aus, "Brotkarte", "x", 10, "1 Brot gratis");
        assertThat(aus.isAnmeldungOhneMail()).isFalse();

        CustomerCard cc = kundenDienst(card, mail, customerRepo, ccRepo).registerOhneMail(card.getId(), null);
        assertThat(cc.getCustomer().isOhneMail()).isTrue();
        verifyNoInteractions(mail);
    }

    // ── 4: Erinnerung an die Testphase ──────────────────────────────────

    @Test
    void testphasenErinnerung_ueberspringtDenLaden() {
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
        assertThat(dienst.lauf(mittag)).isEqualTo(1);
        verify(mail).sendTestphaseMail(any(), any(), anyBoolean(), anyString());
        assertThat(aus.getTestMailStufe()).isZero();
        assertThat(an.getTestMailStufe()).isPositive();

        // Und als Netz: selbst ein direkter Aufruf sendet nichts.
        assertThat(mailer.sendTestphaseMail(aus, mittag, false, "https://app.test")).isFalse();
        verify(sender, never()).send(any(MimeMessage.class));
    }

    // ── 5: Loesch-Mail ──────────────────────────────────────────────────

    private PublicEmailController loeschSeite(Customer kunde, List<CustomerCard> karten, EmailService mail) {
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        when(customerRepo.findById(kunde.getId())).thenReturn(Optional.of(kunde));
        CustomerCardRepository ccRepo = mock(CustomerCardRepository.class);
        when(ccRepo.findByCustomer(kunde)).thenReturn(karten);
        return new PublicEmailController(customerRepo, ccRepo, mock(CustomerService.class), mail);
    }

    @Test
    void loeschMail_nurKartenBeiLaedenMitMailsAus_gehtNichtRaus() {
        Customer kunde = Customer.create("Ayşe", "ayse@example.com");
        Card karteAus = Card.create(aus, "Brotkarte", "x", 10, "Brot");
        EmailService mail = mock(EmailService.class);

        loeschSeite(kunde, List.of(CustomerCard.create(kunde, karteAus)), mail).deleteRequest(kunde.getId());
        verify(mail, never()).sendDeletionMail(any());
    }

    @Test
    void loeschMail_sobaldEinLadenMailsAnHat_gehtSieRaus() {
        Customer kunde = Customer.create("Ayşe", "ayse@example.com");
        Card karteAus = Card.create(aus, "Brotkarte", "x", 10, "Brot");
        Card karteAn = Card.create(an, "Kaffee", "x", 10, "Kaffee");
        EmailService mail = mock(EmailService.class);

        loeschSeite(kunde, List.of(CustomerCard.create(kunde, karteAus), CustomerCard.create(kunde, karteAn)),
                mail).deleteRequest(kunde.getId());
        verify(mail).sendDeletionMail(kunde);

        // Kunde ganz ohne Karte: wie bisher.
        EmailService mail2 = mock(EmailService.class);
        loeschSeite(kunde, List.of(), mail2).deleteRequest(kunde.getId());
        verify(mail2).sendDeletionMail(kunde);
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
