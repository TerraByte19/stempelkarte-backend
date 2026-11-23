package com.example.stemplekarte.controller;

import com.example.stemplekarte.i18n.Texts;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.CustomerRepository;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.service.EmailService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;

/**
 * Öffentliche Endpunkte für Links aus E-Mails (Kunde klickt im Mail-Programm).
 * Liefern kleine HTML-Seiten zurück — kein Login nötig.
 *
 * WICHTIG: /mail/** muss in der SecurityConfig mit permitAll() freigegeben werden:
 *   .requestMatchers("/mail/**").permitAll()
 */
@Tag(name = "Mail-Links", description = "Bestätigen, Löschen, Abmelden per E-Mail-Link")
@Controller
@RequestMapping("/mail")
public class PublicEmailController {

    private final CustomerRepository customerRepository;
    private final CustomerCardRepository customerCardRepository;
    private final CustomerService customerService;
    private final EmailService emailService;

    public PublicEmailController(CustomerRepository customerRepository,
                                 CustomerCardRepository customerCardRepository,
                                 CustomerService customerService,
                                 EmailService emailService) {
        this.customerRepository = customerRepository;
        this.customerCardRepository = customerCardRepository;
        this.customerService = customerService;
        this.emailService = emailService;
    }

    // ── 1. E-Mail bestätigen (Double-Opt-In) ─────────────────────────────
    // Wenn customerId + cardId mitgegeben werden (kommt aus der
    // Bestätigungs-Mail), wird direkt zur Stempelkarte (Apple/Google
    // Wallet Buttons) weitergeleitet — der Link "bringt die Karte aufs Handy".
    @GetMapping("/confirm")
    public ResponseEntity<String> confirm(@RequestParam String token,
                                          @RequestParam(value = "customerId", required = false) String customerId,
                                          @RequestParam(value = "cardId", required = false) String cardId) {
        java.util.Optional<Customer> kunde = customerRepository.findByConfirmToken(token);
        kunde.ifPresent(c -> {
            c.confirmEmail();
            customerRepository.save(c);
        });
        String lang = Texts.sprache(kunde.orElse(null), null);

        if (customerId != null && !customerId.isBlank() && cardId != null && !cardId.isBlank()) {
            return ResponseEntity.status(302)
                    .location(URI.create("/karte/" + customerId + "/" + cardId))
                    .body("");
        }

        return page(lang, "✅", Texts.t(lang, "page.confirmed_title"), Texts.t(lang, "page.confirmed_text"));
    }

    // ── 2. Löschung anfordern (Schritt 1: löst die Sicherheits-Mail aus) ──
    @GetMapping("/delete-request")
    public ResponseEntity<String> deleteRequest(@RequestParam("c") String customerId) {
        java.util.Optional<Customer> kunde = customerRepository.findById(customerId);
        kunde.ifPresent(c -> {
            c.startDeletion();
            customerRepository.save(c);
            emailService.sendDeletionMail(c);
        });
        // Immer dieselbe Antwort — verrät nicht, ob das Konto existiert
        String lang = Texts.sprache(kunde.orElse(null), null);
        return page(lang, "📧", Texts.t(lang, "page.delete_sent_title"), Texts.t(lang, "page.delete_sent_text"));
    }

    // ── 3. Löschung endgültig bestätigen (Schritt 2) ─────────────────────
    @GetMapping("/delete-confirm")
    public ResponseEntity<String> deleteConfirm(@RequestParam String token) {
        return customerRepository.findByDeleteToken(token)
                .map(c -> {
                    String lang = Texts.sprache(c, null);
                    customerService.deleteCustomerData(c.getId());
                    return page(lang, "🗑️", Texts.t(lang, "page.deleted_title"), Texts.t(lang, "page.deleted_text"));
                })
                .orElseGet(() -> page(Texts.STANDARD, "⚠️", Texts.t(Texts.STANDARD, "page.invalid_title"),
                        Texts.t(Texts.STANDARD, "page.delete_invalid_text")));
    }

    // ── 4. Vom Newsletter eines Ladens abmelden ──────────────────────────
    @GetMapping("/unsubscribe")
    public ResponseEntity<String> unsubscribe(@RequestParam("cc") String customerCardId,
                                              @RequestParam("t") String authToken) {
        return customerCardRepository.findById(customerCardId)
                .filter(cc -> cc.getAuthToken().equals(authToken))
                .map(cc -> {
                    // Abmelden gilt fuer den LADEN, nicht fuer eine einzelne Karte.
                    // Wer zwei Karten desselben Ladens hat, bekaeme sonst weiter
                    // Post - obwohl auf dieser Seite das Gegenteil steht.
                    String shopId = cc.getCard().getShop().getId();
                    customerCardRepository.findByCustomer(cc.getCustomer()).stream()
                            .filter(k -> k.getCard().getShop().getId().equals(shopId))
                            .filter(CustomerCard::isMarketingConsent)
                            .forEach(k -> {
                                k.revokeMarketingConsent();
                                customerCardRepository.save(k);
                            });
                    String lang = Texts.sprache(cc.getCustomer(), cc.getCard().getShop());
                    return page(lang, "✅", Texts.t(lang, "page.unsub_title"),
                            Texts.t(lang, "page.unsub_text", esc(cc.getCard().getShop().getName())));
                })
                .orElseGet(() -> page(Texts.STANDARD, "⚠️", Texts.t(Texts.STANDARD, "page.invalid_title"),
                        Texts.t(Texts.STANDARD, "page.unsub_invalid_text")));
    }

    // ── kleine HTML-Antwortseite ─────────────────────────────────────────
    private ResponseEntity<String> page(String lang, String icon, String title, String text) {
        String html = "<!doctype html><html" + Texts.htmlAttrs(lang) + "><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(title) + " – Stampit</title></head>"
                + "<body style=\"margin:0;min-height:100vh;display:flex;align-items:center;"
                + "justify-content:center;background:#f5f5f7;font-family:-apple-system,Segoe UI,sans-serif\">"
                + "<div style=\"background:white;border-radius:16px;padding:40px;max-width:380px;"
                + "text-align:center;box-shadow:0 4px 24px rgba(0,0,0,0.08);margin:16px\">"
                + "<div style=\"font-size:48px;margin-bottom:12px\">" + icon + "</div>"
                + "<h1 style=\"font-size:22px;margin:0 0 8px;color:#1a1a1a\">" + esc(title) + "</h1>"
                + "<p style=\"font-size:14px;color:#666;line-height:1.5;margin:0\">" + text + "</p>"
                + "</div></body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}