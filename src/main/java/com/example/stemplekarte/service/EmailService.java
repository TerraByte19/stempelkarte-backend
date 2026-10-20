package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.Shop;
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
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Versendet alle E-Mails der Plattform über SMTP (z.B. Brevo).
 *
 * Absender-Adresse = MAIL_FROM (zentral, bei Brevo verifiziert).
 * Absender-Name    = Laden-Name (der Kunde sieht "Café Mocca").
 * Reply-To         = E-Mail des Ladens (Antworten landen beim Laden).
 *
 * Solange MAIL_ENABLED=false ist, wird nur geloggt statt gesendet —
 * so läuft die App auch ohne fertiges Brevo-Setup.
 *
 * BRANDING: Mails, die im Namen eines Ladens verschickt werden
 * (Bestätigung, Newsletter, "Karte voll"), zeigen automatisch das
 * Hero-Bild und Logo des Ladens im Mail-Header, falls vorhanden.
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

    // Globales Tages-Limit für ALLE versendeten Mails (Schutz gegen
    // verteilte Angriffe + Brevo-Kontingent). Per ENV anpassbar, wenn du
    // den Brevo-Plan upgradest (z.B. von 300 auf 20000).
    // Standard 300 = Brevo-Free-Limit.
    @Value("${stempelkarte.mail.daily-limit:300}")
    private int dailyLimit;

    // Zähler für heute versendete Mails. Wird automatisch zurückgesetzt,
    // sobald ein neuer Tag beginnt. Liegt im Arbeitsspeicher — bei einem
    // Server-Neustart (Render-Deploy) startet der Zähler bei 0, was
    // unkritisch ist (im schlimmsten Fall ein paar Mails mehr).
    private final AtomicInteger sentToday = new AtomicInteger(0);
    private volatile LocalDate counterDate = LocalDate.now();
    // Damit die 80%-Warnung nur einmal pro Tag geloggt wird:
    private volatile boolean warned = false;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // ── 1. Bestätigungs-Mail (Double-Opt-In) ─────────────────────────────
    // Der Bestätigungs-Link bestätigt die E-Mail UND führt direkt zur
    // Stempelkarte (mit Apple/Google Wallet Buttons), da customerId+cardId
    // mitgegeben werden. Zeigt Hero-Bild + Logo des Ladens im Header.
    @Async
    public void sendConfirmationMail(Customer customer, Shop shop, String cardId) {
        if (customer.getConfirmToken() == null) return; // schon bestätigt

        String shopName = shop.getName();
        String confirmUrl = baseUrl + "/mail/confirm?token=" + customer.getConfirmToken()
                + "&customerId=" + customer.getId() + "&cardId=" + cardId;
        String deleteUrl  = baseUrl + "/mail/delete-request?c=" + customer.getId();

        String html = seite(
                shop,
                "Ein Klick, und deine Stempelkarte ist auf dem Handy.",
                textBlock("Deine Stempelkarte bei " + shopName,
                        "Hallo " + esc(customer.getName()) + ",<br><br>"
                                + "du hast dich für die digitale Stempelkarte von <b>" + esc(shopName)
                                + "</b> angemeldet. Bestätige kurz deine E-Mail-Adresse, dann liegt die Karte "
                                + "in deiner Wallet.")
                        + knopfBlock("Stempelkarte aufs Handy holen", confirmUrl,
                        shop.getColorBackground() != null ? shop.getColorBackground() : STAMPIT_FARBE)
                        + textBlock(null,
                        "<span style=\"font-size:13px;color:#8a8a95\">"
                                + "Wenn du das nicht warst, ignoriere diese Mail einfach.</span>"),
                deleteUrl
        );

        send(customer.getEmail(), shopName, null,
                "Deine Stempelkarte – " + shopName, html);
    }

    // ── 2. Lösch-Bestätigungs-Mail (Schritt 2, Sicherheits-Mail) ─────────
    // Generische StampIT-Mail, kein Laden-Branding (kontoweit, nicht
    // kartenspezifisch).
    @Async
    public void sendDeletionMail(Customer customer) {
        String deleteConfirmUrl = baseUrl + "/mail/delete-confirm?token=" + customer.getDeleteToken();

        String html = seite(
                null,
                "Löschung deiner Daten bestätigen.",
                textBlock("Löschung bestätigen",
                        "Hallo " + esc(customer.getName()) + ",<br><br>"
                                + "du hast die Löschung deiner Daten angefordert. <b>Achtung:</b> Damit werden "
                                + "dein Konto, alle Stempelkarten und alle gesammelten Stempel unwiderruflich "
                                + "gelöscht.")
                        + knopfBlock("Meine Daten endgültig löschen", deleteConfirmUrl, "#c0392b")
                        + textBlock(null,
                        "<span style=\"font-size:13px;color:#8a8a95\">"
                                + "Wenn du das nicht warst, ignoriere diese Mail - dann passiert nichts.</span>"),
                null
        );

        send(customer.getEmail(), "StampIT", null,
                "Löschung bestätigen – StampIT", html);
    }

    // ── 3. Newsletter / Werbe-Mail eines Ladens ──────────────────────────

    /**
     * Was der Laden geschrieben hat. Gilt fuer alle Empfaenger gleich.
     *
     * ueberschrift, knopfText und knopfUrl sind optional - leer heisst,
     * der Block faellt weg. Ein Knopf ohne Ziel waere ein toter Klick,
     * deshalb zaehlt nur, wenn beides da ist.
     */
    public record NewsletterInhalt(String subject, String ueberschrift, String text,
                                   List<String> imageUrls, boolean bilderUeberText,
                                   String knopfText, String knopfUrl) {}

    /** Was sich pro Empfaenger unterscheidet. */
    public record NewsletterEmpfaenger(String email, int stempel, int benoetigt,
                                       String belohnung, String unsubscribeUrl,
                                       String deleteUrl) {}

    // unsubscribeUrl ist Pflicht (UWG): jeder Empfänger kann sich abmelden.
    //
    // Bewusst NICHT @Async: der Aufrufer (NewsletterService) laeuft schon im
    // Hintergrund und braucht pro Empfaenger das echte Ergebnis. Gibt true
    // zurueck, wenn der Mailserver die Mail angenommen hat.
    public boolean sendNewsletterMail(Shop shop, String replyTo,
                                      NewsletterInhalt inhalt, NewsletterEmpfaenger e) {
        String shopName = shop.getName();
        String farbe = (shop.getColorBackground() != null) ? shop.getColorBackground() : STAMPIT_FARBE;

        StringBuilder bilder = new StringBuilder();
        if (inhalt.imageUrls() != null) {
            for (String url : inhalt.imageUrls()) {
                if (url == null || url.isBlank()) continue;
                bilder.append(bildBlock(url));
            }
        }

        String text = textBlock(inhalt.ueberschrift(),
                esc(inhalt.text()).replace("\n", "<br>"));

        StringBuilder inneres = new StringBuilder();
        if (inhalt.bilderUeberText()) inneres.append(bilder).append(text);
        else inneres.append(text).append(bilder);

        inneres.append(stempelstand(e, farbe));

        // Knopf nur, wenn der Laden Text UND Ziel gesetzt hat.
        if (inhalt.knopfText() != null && !inhalt.knopfText().isBlank()
                && inhalt.knopfUrl() != null && !inhalt.knopfUrl().isBlank()) {
            inneres.append(knopfBlock(inhalt.knopfText(), inhalt.knopfUrl(), farbe));
        }

        String html = seite(
                shop,
                inhalt.ueberschrift() != null && !inhalt.ueberschrift().isBlank()
                        ? inhalt.ueberschrift() : inhalt.text(),
                inneres.toString(),
                e.deleteUrl(),
                "<a href=\"" + attr(e.unsubscribeUrl()) + "\" style=\"color:#8a8a95\">"
                        + "Keine Angebote mehr von " + esc(shopName) + " erhalten (abmelden)</a>"
        );

        return send(e.email(), shopName, replyTo, inhalt.subject(), html);
    }

    /**
     * "Dein Stand: 7 von 10 Stempeln". Der Grund, warum diese Mail nicht
     * wie Werbung wirkt: sie sagt dem Gast etwas ueber ihn selbst.
     */
    private String stempelstand(NewsletterEmpfaenger e, String farbe) {
        if (e.benoetigt() <= 0) return "";
        int stand = Math.min(e.stempel(), e.benoetigt());
        int fehlen = e.benoetigt() - stand;

        String zweiteZeile;
        if (fehlen == 0) {
            zweiteZeile = (e.belohnung() != null && !e.belohnung().isBlank())
                    ? esc(e.belohnung()) + " wartet auf dich."
                    : "Deine Belohnung wartet auf dich.";
        } else if (e.belohnung() != null && !e.belohnung().isBlank()) {
            zweiteZeile = "Noch " + fehlen + " bis: " + esc(e.belohnung()) + ".";
        } else {
            zweiteZeile = "Noch " + fehlen + " bis zur Belohnung.";
        }

        return infoBlock("Dein Stand: <b style=\"color:" + attr(farbe) + ";\">"
                + stand + " von " + e.benoetigt() + " Stempeln</b><br>"
                + "<span style=\"font-size:14px;color:#70707c;\">" + zweiteZeile + "</span>");
    }

    // ── intern ────────────────────────────────────────────────────────────

    /**
     * Gibt true zurueck, wenn der Mailserver die Mail angenommen hat.
     * false heisst: nicht rausgegangen (deaktiviert, Limit, Fehler) — der
     * Aufrufer kann das dem Laden ehrlich anzeigen statt stillschweigend
     * "versendet" zu melden.
     */
    private boolean send(String to, String fromName, String replyTo, String subject, String html) {
        if (!enabled) {
            log.info("MAIL deaktiviert (MAIL_ENABLED=false) — würde senden an {}: '{}'", to, subject);
            return false;
        }
        if (from == null || from.isBlank()) {
            log.warn("MAIL_FROM fehlt — Mail an {} nicht gesendet", to);
            return false;
        }
        // Globales Tages-Limit prüfen (Schutz fürs Mail-Kontingent).
        if (!reserveDailySlot()) {
            log.error("TAGES-MAILLIMIT erreicht ({}/{}) — Mail an {} NICHT gesendet: '{}'",
                    dailyLimit, dailyLimit, to, subject);
            return false;
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
            return true;
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            log.error("Mail-Versand an {} fehlgeschlagen: {}", to, e.getMessage());
            return false;
        }
    }

    /**
     * Reserviert einen Platz im heutigen Mail-Kontingent. Gibt true zurück,
     * wenn noch Platz ist (Mail darf raus), sonst false (Limit erreicht).
     *
     * - Setzt den Zähler automatisch zurück, wenn ein neuer Tag begonnen hat.
     * - Loggt eine Warnung, sobald 80% des Limits erreicht sind (einmal/Tag),
     *   damit du rechtzeitig den Brevo-Plan upgraden kannst.
     * synchronized, damit Tageswechsel + Zählung threadsicher sind.
     */
    private synchronized boolean reserveDailySlot() {
        LocalDate today = LocalDate.now();
        if (!today.equals(counterDate)) {
            // Neuer Tag → Zähler zurücksetzen
            counterDate = today;
            sentToday.set(0);
            warned = false;
        }

        int current = sentToday.get();
        if (current >= dailyLimit) {
            return false; // Limit erreicht
        }

        int afterThis = sentToday.incrementAndGet();

        // Warnung bei 80% (nur einmal pro Tag)
        if (!warned && afterThis >= (int) (dailyLimit * 0.8)) {
            warned = true;
            log.warn("⚠️ MAIL-KONTINGENT zu 80% ausgeschöpft ({}/{}). "
                    + "Bei weiterem Wachstum Brevo-Plan upgraden und "
                    + "stempelkarte.mail.daily-limit erhöhen.", afterThis, dailyLimit);
        }
        return true;
    }

    // ── Mail-Layout ───────────────────────────────────────────────────────
    //
    // Mail-Clients sind kein Browser. Gmail und Outlook werfen display:flex,
    // gap und die meisten modernen Eigenschaften weg - deshalb liegt das
    // Layout auf verschachtelten Tabellen mit Inline-Styles. Sieht nach 2005
    // aus, ist aber das Einzige, was ueberall gleich ankommt.

    private static final String SCHRIFT =
            "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    private static final String STAMPIT_FARBE = "#3C3489";

    /**
     * Rahmen der Mail: Grundgeruest, Markenband des Ladens, Inhalt, Fuss.
     *
     * vorschauzeile ist der Text, den die Inbox neben dem Betreff anzeigt.
     * Ohne den zeigt sie den Anfang des Fliesstextes - oft "Hallo Alex,".
     */
    private String seite(Shop shop, String vorschauzeile, String inhalt,
                         String deleteUrl, String... extraFooter) {
        StringBuilder fuss = new StringBuilder();
        fuss.append("Diese Mail wurde über StampIT (digitale Stempelkarten) versendet.");
        for (String zeile : extraFooter) {
            fuss.append("<br>").append(zeile);
        }
        if (deleteUrl != null) {
            fuss.append("<br><a href=\"").append(attr(deleteUrl)).append("\" style=\"color:#8a8a95\">")
                    .append("Ich möchte mein Konto und meine Daten löschen</a>");
        }

        return "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                // Haelt Gmail und Apple Mail davon ab, die Mail im Dark Mode
                // selbst umzufaerben - sonst weisse Bilder auf invertiertem Text.
                + "<meta name=\"color-scheme\" content=\"light only\">"
                + "<meta name=\"supported-color-schemes\" content=\"light only\">"
                + "</head><body style=\"margin:0;padding:0;background:#ececed;\">"
                + vorschau(vorschauzeile)
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"background:#ececed;\"><tr><td align=\"center\" style=\"padding:20px 10px;\">"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"width:100%;max-width:600px;background:#ffffff;border-radius:16px;"
                + "overflow:hidden;font-family:" + SCHRIFT + ";\">"
                + markenband(shop)
                + inhalt
                + "<tr><td style=\"padding:28px 22px 26px;\">"
                + "<div style=\"border-top:1px solid #ececf0;padding-top:16px;font-size:12px;"
                + "line-height:1.8;color:#8a8a95;\">" + fuss + "</div>"
                + "</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    /** Unsichtbare Vorschauzeile fuer die Inbox-Liste. */
    private String vorschau(String text) {
        if (text == null || text.isBlank()) return "";
        return "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0\">"
                + esc(kuerzen(text, 120)) + "</div>";
    }

    /**
     * Farbiges Band mit Logo und Laden-Name. Die Farben kommen aus dem
     * Kartendesign des Ladens, damit Mail und Wallet-Karte zusammenpassen.
     *
     * Das Hero-Bild taucht hier bewusst NICHT auf: es ist der Apple-Wallet-
     * Streifen, hart auf 1125x369 zugeschnitten. In einer Mail waere es ein
     * angeschnittenes Logo.
     */
    private String markenband(Shop shop) {
        String hintergrund = (shop != null && shop.getColorBackground() != null)
                ? shop.getColorBackground() : STAMPIT_FARBE;
        String vordergrund = (shop != null && shop.getColorForeground() != null)
                ? shop.getColorForeground() : "#FFFFFF";
        String name = (shop != null) ? shop.getName() : "StampIT";
        String logoUrl = (shop != null) ? shop.getLogoUrl() : null;

        StringBuilder b = new StringBuilder();
        b.append("<tr><td style=\"background:").append(attr(hintergrund)).append(";padding:16px 22px;\">")
                .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>");
        if (logoUrl != null && !logoUrl.isBlank()) {
            b.append("<td width=\"46\" style=\"padding-right:12px;\">")
                    .append("<img src=\"").append(attr(logoUrl)).append("\" width=\"46\" height=\"46\" alt=\"\" ")
                    .append("style=\"display:block;width:46px;height:46px;border-radius:12px;background:#ffffff;\">")
                    .append("</td>");
        }
        b.append("<td style=\"color:").append(attr(vordergrund))
                .append(";font-size:17px;font-weight:700;letter-spacing:0.2px;\">")
                .append(esc(name)).append("</td>");
        b.append("</tr></table></td></tr>");
        return b.toString();
    }

    /** Ueberschrift (optional) und Fliesstext. */
    private String textBlock(String ueberschrift, String textHtml) {
        StringBuilder b = new StringBuilder("<tr><td style=\"padding:28px 22px 0;\">");
        if (ueberschrift != null && !ueberschrift.isBlank()) {
            b.append("<h1 style=\"margin:0 0 10px;font-size:22px;line-height:1.3;")
                    .append("color:#15151a;font-weight:700;\">").append(esc(ueberschrift)).append("</h1>");
        }
        b.append("<p style=\"margin:0;font-size:16px;line-height:1.65;color:#3b3b44;\">")
                .append(textHtml).append("</p></td></tr>");
        return b.toString();
    }

    /**
     * Bild in voller Breite. Der duenne Rahmen ist kein Zierrat: Produktfotos
     * sind meist freigestellt auf Weiss und wuerden sonst randlos in die
     * weisse Karte auslaufen.
     */
    private String bildBlock(String url) {
        return "<tr><td style=\"padding:22px 22px 0;\">"
                + "<img src=\"" + attr(url) + "\" alt=\"\" width=\"556\" "
                + "style=\"display:block;width:100%;height:auto;border-radius:12px;"
                + "border:1px solid #e6e6ea;\"></td></tr>";
    }

    /** Grauer Kasten, z.B. fuer den Stempelstand. */
    private String infoBlock(String innenHtml) {
        return "<tr><td style=\"padding:24px 22px 0;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"background:#f5f5f8;border-radius:12px;\"><tr>"
                + "<td style=\"padding:15px 18px;font-size:15px;line-height:1.5;color:#3b3b44;\">"
                + innenHtml + "</td></tr></table></td></tr>";
    }

    /** Knopf. Tabelle statt Link mit Padding, sonst bleibt Outlook die Flaeche schuldig. */
    private String knopfBlock(String text, String url, String farbe) {
        return "<tr><td style=\"padding:22px 22px 0;\">"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                + "<td style=\"background:" + attr(farbe) + ";border-radius:10px;\">"
                + "<a href=\"" + attr(url) + "\" style=\"display:inline-block;padding:14px 26px;"
                + "color:#ffffff;text-decoration:none;font-weight:600;font-size:15px;\">"
                + esc(text) + "</a></td></tr></table></td></tr>";
    }

    private String kuerzen(String s, int max) {
        String t = s.replace("\n", " ").trim();
        return (t.length() <= max) ? t : t.substring(0, max - 1) + "…";
    }

    /** Wert fuer ein HTML-Attribut entschaerfen (URLs, Farben aus Nutzereingaben). */
    private String attr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    /** HTML-Sonderzeichen entschärfen (Nutzereingaben in Mails). */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}