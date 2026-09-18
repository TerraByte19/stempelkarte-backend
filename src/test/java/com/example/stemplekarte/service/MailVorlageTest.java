package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mail-Clients sind kein Browser. Gmail und Outlook werfen display:flex und
 * gap weg - in der ersten Fassung klebte deshalb das Logo am Laden-Namen.
 * Diese Tests halten fest, dass das Layout auf Tabellen liegt und die
 * Bausteine wirklich im HTML landen.
 *
 * Nebenbei wird das erzeugte HTML nach target/ geschrieben, damit man sich
 * die Mail im Browser ansehen kann, ohne eine zu verschicken.
 */
class MailVorlageTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final EmailService mailer = new EmailService(sender);

    private Shop laden() {
        Shop s = Shop.create("laden@test.de", "hash", "UTOPIA COFFE", 3);
        s.update(null, "https://bilder.test/logo.png", "#3C3489", "#FFFFFF", "#FAC875");
        return s;
    }

    /** Sendet einmal und gibt das erzeugte HTML zurueck. */
    private String gesendetesHtml(EmailService.NewsletterInhalt inhalt,
                                  EmailService.NewsletterEmpfaenger empfaenger) throws Exception {
        when(sender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        ReflectionTestUtils.setField(mailer, "enabled", true);
        ReflectionTestUtils.setField(mailer, "from", "info@stampit-app.de");
        // Ohne Spring-Kontext bleiben @Value-Felder auf 0 - das Tageslimit
        // waere sofort erreicht und send() wuerde gar nicht erst senden.
        ReflectionTestUtils.setField(mailer, "dailyLimit", 300);

        boolean ok = mailer.sendNewsletterMail(laden(), "laden@test.de", inhalt, empfaenger);
        assertThat(ok).isTrue();

        var fang = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(fang.capture());
        return (String) fang.getValue().getContent();
    }

    private EmailService.NewsletterEmpfaenger gast(int stempel, int benoetigt) {
        return new EmailService.NewsletterEmpfaenger("alex@test.de", stempel, benoetigt,
                "Gratis Kaffee", "https://stampit-app.de/mail/unsubscribe?cc=1&t=2",
                "https://stampit-app.de/mail/delete-request?c=3");
    }

    @Test
    void layoutLiegtAufTabellenUndTraegtDieFarbenDesLadens() throws Exception {
        String html = gesendetesHtml(
                new EmailService.NewsletterInhalt("Betreff", "Burger-Menü", "Hallo,\nzwei Zeilen.",
                        List.of("https://bilder.test/burger.jpg"), false, null, null),
                gast(7, 10));

        Files.writeString(Path.of("target", "mail-vorschau.html"), html);

        assertThat(html).doesNotContain("display:flex").doesNotContain("gap:");
        assertThat(html).contains("<table");
        assertThat(html).contains("background:#3C3489");          // Farbe aus dem Kartendesign
        assertThat(html).contains("UTOPIA COFFE");
        assertThat(html).contains("https://bilder.test/burger.jpg");
        assertThat(html).contains("color-scheme");                 // kein Dark-Mode-Umfaerben
        assertThat(html).contains("Zeilenumbruch".substring(0, 0) + "zwei Zeilen");
        assertThat(html).contains("<br>");                         // Umbruch aus dem Fliesstext
    }

    @Test
    void stempelstandStehtInDerMail() throws Exception {
        String html = gesendetesHtml(
                new EmailService.NewsletterInhalt("Betreff", null, "Text", List.of(), false, null, null),
                gast(7, 10));

        assertThat(html).contains("7 von 10 Stempeln");
        assertThat(html).contains("Noch 3 bis: Gratis Kaffee.");
    }

    @Test
    void volleKarteWirdNichtUeberzaehltUndKeinKnopfOhneZiel() throws Exception {
        String html = gesendetesHtml(
                new EmailService.NewsletterInhalt("Betreff", null, "Text", List.of(), false,
                        "Zur Karte", null),   // Text ohne Ziel = kein Knopf
                gast(14, 10));

        assertThat(html).contains("10 von 10 Stempeln");   // nicht "14 von 10"
        assertThat(html).contains("Gratis Kaffee wartet auf dich.");
        assertThat(html).doesNotContain("Zur Karte");
    }

    @Test
    void knopfErscheintMitTextUndZiel() throws Exception {
        String html = gesendetesHtml(
                new EmailService.NewsletterInhalt("Betreff", null, "Text", List.of(), false,
                        "Zur Speisekarte", "https://utopia.test/karte"),
                gast(2, 10));

        assertThat(html).contains("Zur Speisekarte");
        assertThat(html).contains("https://utopia.test/karte");
    }
}
