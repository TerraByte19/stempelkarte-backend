package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Customer;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

/**
 * Versendet alle E-Mails der Plattform über SMTP (z.B. Brevo).
 *
 * Absender-Adresse = MAIL_FROM (zentral, bei Brevo verifiziert).
 * Absender-Name    = Laden-Name (der Kunde sieht "Café Mocca").
 * Reply-To         = E-Mail des Ladens (Antworten landen beim Laden).
 *
 * Solange MAIL_ENABLED=false ist, wird nur geloggt statt gesendet —
 * so läuft die App auch ohne fertiges Brevo-Setup.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${stempelkarte.mail.enabled:false}")
    private boolean enabled;

    @Value("${stempelkarte.mail.from:}")
    private String from;

    @Value("${stempelkarte.base-url:http://localhost:8080}")
    private String baseUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // ── 1. Bestätigungs-Mail (Double-Opt-In) ─────────────────────────────
    // Enthält den Bestätigungs-Link UND den "Daten löschen"-Link.
    @Async
    public void sendConfirmationMail(Customer customer, String shopName) {
        if (customer.getConfirmToken() == null) return; // schon bestätigt

        String confirmUrl = baseUrl + "/mail/confirm?token=" + customer.getConfirmToken();
        String deleteUrl  = baseUrl + "/mail/delete-request?c=" + customer.getId();

        String html = wrap(
                "Hallo " + esc(customer.getName()) + ",",
                "<p>du hast dich für die digitale Stempelkarte von <b>" + esc(shopName) + "</b> angemeldet.</p>"
                        + "<p>Bitte bestätige deine E-Mail-Adresse, damit wir dir Angebote senden dürfen:</p>"
                        + button(confirmUrl, "E-Mail bestätigen")
                        + "<p style=\"font-size:13px;color:#888\">Wenn du das nicht warst, ignoriere diese Mail einfach.</p>",
                deleteUrl
        );

        send(customer.getEmail(), shopName, null,
                "Bitte bestätige deine E-Mail – " + shopName, html);
    }

    // ── 2. Lösch-Bestätigungs-Mail (Schritt 2, Sicherheits-Mail) ─────────
    @Async
    public void sendDeletionMail(Customer customer) {
        String deleteConfirmUrl = baseUrl + "/mail/delete-confirm?token=" + customer.getDeleteToken();

        String html = wrap(
                "Hallo " + esc(customer.getName()) + ",",
                "<p>du hast die Löschung deiner Daten angefordert.</p>"
                        + "<p><b>Achtung:</b> Damit werden dein Konto, alle Stempelkarten und alle "
                        + "gesammelten Stempel unwiderruflich gelöscht.</p>"
                        + button(deleteConfirmUrl, "Meine Daten endgültig löschen")
                        + "<p style=\"font-size:13px;color:#888\">Wenn du das nicht warst, ignoriere diese Mail — dann passiert nichts.</p>",
                null
        );

        send(customer.getEmail(), "StampIT", null,
                "Löschung bestätigen – StampIT", html);
    }

    // ── 3. Newsletter / Werbe-Mail eines Ladens ──────────────────────────
    // unsubscribeUrl ist Pflicht (UWG): jeder Empfänger kann sich abmelden.
    @Async
    public void sendNewsletterMail(String to, String shopName, String replyTo,
                                   String subject, String bodyText,
                                   String unsubscribeUrl, String deleteUrl) {
        String html = wrap(
                null,
                "<p style=\"white-space:pre-line\">" + esc(bodyText) + "</p>",
                deleteUrl,
                "<a href=\"" + unsubscribeUrl + "\" style=\"color:#888\">Keine Angebote mehr von "
                        + esc(shopName) + " erhalten (abmelden)</a>"
        );

        send(to, shopName, replyTo, subject, html);
    }

    // ── intern ────────────────────────────────────────────────────────────

    private void send(String to, String fromName, String replyTo, String subject, String html) {
        if (!enabled) {
            log.info("MAIL deaktiviert (MAIL_ENABLED=false) — würde senden an {}: '{}'", to, subject);
            return;
        }
        if (from == null || from.isBlank()) {
            log.warn("MAIL_FROM fehlt — Mail an {} nicht gesendet", to);
            return;
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, "UTF-8");
            h.setFrom(new InternetAddress(from, fromName));
            h.setTo(to);
            if (replyTo != null && !replyTo.isBlank()) h.setReplyTo(replyTo);
            h.setSubject(subject);
            h.setText(html, true);
            mailSender.send(msg);
            log.info("Mail gesendet an {}: '{}'", to, subject);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            log.error("Mail-Versand an {} fehlgeschlagen: {}", to, e.getMessage());
        }
    }

    /** Einheitliches Mail-Layout. Footer-Zeilen (Lösch-Link, Abmelde-Link) optional. */
    private String wrap(String greeting, String content, String deleteUrl, String... extraFooter) {
        StringBuilder footer = new StringBuilder();
        for (String line : extraFooter) {
            footer.append("<div style=\"margin-top:6px\">").append(line).append("</div>");
        }
        if (deleteUrl != null) {
            footer.append("<div style=\"margin-top:6px\"><a href=\"").append(deleteUrl)
                    .append("\" style=\"color:#888\">Ich möchte mein Konto und meine Daten löschen</a></div>");
        }
        return "<div style=\"font-family:-apple-system,Segoe UI,sans-serif;max-width:520px;margin:0 auto;"
                + "padding:24px;color:#1a1a1a\">"
                + (greeting != null ? "<p>" + greeting + "</p>" : "")
                + content
                + "<hr style=\"border:none;border-top:1px solid #eee;margin:24px 0 12px\">"
                + "<div style=\"font-size:12px;color:#999\">"
                + "Diese Mail wurde über StampIT (digitale Stempelkarten) versendet."
                + footer
                + "</div></div>";
    }

    private String button(String url, String label) {
        return "<p style=\"margin:24px 0\"><a href=\"" + url + "\" "
                + "style=\"background:#3C3489;color:#ffffff;text-decoration:none;"
                + "padding:12px 24px;border-radius:10px;font-weight:600;display:inline-block\">"
                + esc(label) + "</a></p>";
    }

    /** HTML-Sonderzeichen entschärfen (Nutzereingaben in Mails). */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}