package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class LandingController {

    private final CustomerService customerService;
    private final CardService cardService;
    private final GoogleWalletService googleWalletService;

    @Value("${stempelkarte.base-url:http://localhost:8080}")
    private String baseUrl;

    public LandingController(CustomerService customerService, CardService cardService,
                             GoogleWalletService googleWalletService) {
        this.customerService = customerService;
        this.cardService = cardService;
        this.googleWalletService = googleWalletService;
    }

    // ── Karten-Anmeldung mit Werbe-Einwilligung (von /karte-neu/ aufgerufen) ──
    // Ersetzt den alten Aufruf POST /api/customer, weil wir hier zusätzlich
    // - die Karte direkt verknüpfen
    // - die Marketing-Einwilligung pro Karte speichern
    // - die Bestätigungs-Mail (Double-Opt-In) auslösen.
    public record RegisterRequest(String name, String email, String cardId,
                                  boolean marketingConsent) {}

    @PostMapping("/karte-neu/register")
    public Map<String, String> registerForCard(@RequestBody RegisterRequest req) {
        CustomerCard cc = customerService.registerForCard(
                req.name(), req.email(), req.cardId(), req.marketingConsent());
        return Map.of(
                "customerId", cc.getCustomer().getId(),
                "cardId", cc.getCard().getId()
        );
    }

    // Bestehende Kunden-Landing-Page (mit Stempeln)
    @GetMapping(value = "/karte/{customerId}/{cardId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> landingPage(@PathVariable String customerId,
                                              @PathVariable String cardId) {
        try {
            CustomerCard cc = customerService.getOrCreateCustomerCard(customerId, cardId);
            Card card = cc.getCard();
            Customer customer = cc.getCustomer();

            String shopName = card.getShop().getName();
            String bgColor = card.getShop().getColorBackground();
            String logoUrl = card.getShop().getLogoUrl() != null
                    ? card.getShop().getLogoUrl() : "";

            String googleSaveUrl = "";
            try {
                googleSaveUrl = googleWalletService.generateSaveUrl(cc);
            } catch (Exception e) {
                // Google Wallet nicht konfiguriert
            }

            String applePassUrl = baseUrl + "/api/customer/" + customerId
                    + "/card/" + cardId + "/apple-pass";

            int stamps = cc.getStamps();
            int threshold = card.getRewardThreshold();

            StringBuilder stampsHtml = new StringBuilder();
            for (int i = 1; i <= threshold; i++) {
                if (i <= stamps) {
                    stampsHtml.append("<div class='stamp filled'>☕</div>");
                } else {
                    stampsHtml.append("<div class='stamp empty'>").append(i).append("</div>");
                }
            }

            String html = """
                    <!DOCTYPE html>
                    <html lang="de">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>%s — Treuekarte</title>
                        <style>
                            * { margin: 0; padding: 0; box-sizing: border-box; }
                            body {
                                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                                background: #f5f5f7;
                                min-height: 100vh;
                                display: flex;
                                align-items: center;
                                justify-content: center;
                                padding: 20px;
                            }
                            .card {
                                background: %s;
                                border-radius: 20px;
                                padding: 28px;
                                width: 100%%;
                                max-width: 380px;
                                color: white;
                                box-shadow: 0 20px 60px rgba(0,0,0,0.3);
                            }
                            .shop-header {
                                display: flex;
                                align-items: center;
                                gap: 12px;
                                margin-bottom: 24px;
                            }
                            .shop-logo {
                                width: 50px;
                                height: 50px;
                                border-radius: 12px;
                                object-fit: cover;
                                background: rgba(255,255,255,0.2);
                            }
                            .shop-name { font-size: 20px; font-weight: 600; }
                            .card-name { font-size: 13px; opacity: 0.75; margin-top: 2px; }
                            .customer-name { font-size: 14px; opacity: 0.8; margin-bottom: 20px; }
                            .progress {
                                font-size: 13px;
                                opacity: 0.75;
                                margin-bottom: 12px;
                                text-transform: uppercase;
                                letter-spacing: 0.5px;
                            }
                            .stamps-grid {
                                display: grid;
                                grid-template-columns: repeat(5, 1fr);
                                gap: 8px;
                                margin-bottom: 24px;
                            }
                            .stamp {
                                aspect-ratio: 1;
                                border-radius: 50%%;
                                display: flex;
                                align-items: center;
                                justify-content: center;
                                font-size: 18px;
                            }
                            .stamp.filled { background: rgba(255,255,255,0.9); }
                            .stamp.empty {
                                border: 1.5px dashed rgba(255,255,255,0.4);
                                font-size: 12px;
                                opacity: 0.6;
                            }
                            .reward-text {
                                font-size: 14px;
                                opacity: 0.9;
                                margin-bottom: 28px;
                                padding: 12px;
                                background: rgba(255,255,255,0.15);
                                border-radius: 10px;
                                text-align: center;
                            }
                            .wallet-buttons { display: flex; flex-direction: column; gap: 12px; }
                            .btn-apple {
                                display: block;
                                background: black;
                                color: white;
                                text-decoration: none;
                                padding: 14px;
                                border-radius: 12px;
                                text-align: center;
                                font-size: 15px;
                                font-weight: 500;
                            }
                            .btn-google {
                                display: block;
                                background: white;
                                color: #333;
                                text-decoration: none;
                                padding: 14px;
                                border-radius: 12px;
                                text-align: center;
                                font-size: 15px;
                                font-weight: 500;
                            }
                            .hidden { display: none; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <div class="shop-header">
                                %s
                                <div>
                                    <div class="shop-name">%s</div>
                                    <div class="card-name">%s</div>
                                </div>
                            </div>
                            <div class="customer-name">👤 %s</div>
                            <div class="progress">%d von %d Stempeln</div>
                            <div class="stamps-grid">%s</div>
                            <div class="reward-text">%s</div>
                            <div class="wallet-buttons">
                                <a href="%s" class="btn-apple" id="apple-btn">
                                    🍎 Zu Apple Wallet hinzufügen
                                </a>
                                %s
                            </div>
                        </div>
                        <script>
                            // HIER GEÄNDERT: Automatischer Live-Reload, wenn gestempelt wurde
                            const currentStamps = %d;
                            const custId = '%s';
                            const cId = '%s';
                            
                            setInterval(async () => {
                                try {
                                    const res = await fetch(`/api/customer/${custId}/card/${cId}`);
                                    if (res.ok) {
                                        const data = await res.json();
                                        if (data.stamps !== currentStamps) {
                                            window.location.reload();
                                        }
                                    }
                                } catch (e) {}
                            }, 2500);

                            const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent);
                            const isAndroid = /Android/.test(navigator.userAgent);
                            const appleBtn = document.getElementById('apple-btn');
                            const googleBtn = document.getElementById('google-btn');
                            if (!isIOS && appleBtn) appleBtn.classList.add('hidden');
                            if (!isAndroid && googleBtn) googleBtn.classList.add('hidden');
                        </script>
                    </body>
                    </html>
                    """.formatted(
                    shopName, bgColor,
                    logoUrl.isBlank() ? "" : "<img src='" + logoUrl + "' class='shop-logo' alt='Logo'>",
                    shopName, card.getName(),
                    customer.getName(),
                    stamps, threshold,
                    stampsHtml,
                    stamps >= threshold ? "🎉 " + card.getRewardText() + " verfügbar!" :
                            "Noch " + (threshold - stamps) + " Stempel bis: " + card.getRewardText(),
                    applePassUrl,
                    googleSaveUrl.isBlank() ? "" :
                            "<a href='" + googleSaveUrl + "' class='btn-google' id='google-btn'>" +
                                    "🤖 Zu Google Wallet hinzufügen</a>",
                    stamps, customerId, cardId // Parameter für das JS-Polling
            );

            return ResponseEntity.ok(html);

        } catch (Exception e) {
            return ResponseEntity.ok("""
                    <html><body style="font-family:sans-serif;padding:40px;text-align:center">
                    <h2>Karte nicht gefunden</h2>
                    <p>%s</p>
                    </body></html>
                    """.formatted(e.getMessage()));
        }
    }

    // Neue Landing-Page für Kunden ohne ID
    @GetMapping(value = "/karte-neu/{cardId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> newCustomerPage(@PathVariable String cardId) {
        try {
            Card card = cardService.getById(cardId);
            String shopName = card.getShop().getName();
            String bgColor = card.getShop().getColorBackground();
            String logoUrl = card.getShop().getLogoUrl() != null
                    ? card.getShop().getLogoUrl() : "";

            String html = """
                    <!DOCTYPE html>
                    <html lang="de">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>%s — Stempelkarte</title>
                        <style>
                            * { margin: 0; padding: 0; box-sizing: border-box; }
                            body {
                                font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                                background: #f5f5f7;
                                min-height: 100vh;
                                display: flex;
                                align-items: center;
                                justify-content: center;
                                padding: 20px;
                            }
                            .card {
                                background: %s;
                                border-radius: 20px;
                                padding: 28px;
                                width: 100%%;
                                max-width: 380px;
                                color: white;
                                box-shadow: 0 20px 60px rgba(0,0,0,0.3);
                            }
                            .header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
                            .logo { width: 50px; height: 50px; border-radius: 12px; object-fit: cover; }
                            .shop-name { font-size: 20px; font-weight: 600; }
                            .card-name { font-size: 13px; opacity: 0.75; margin-top: 2px; }
                            .info {
                                background: rgba(255,255,255,0.15);
                                border-radius: 12px;
                                padding: 16px;
                                margin-bottom: 20px;
                                text-align: center;
                            }
                            .info-title { font-size: 16px; font-weight: 600; margin-bottom: 6px; }
                            .info-text { font-size: 13px; opacity: 0.85; line-height: 1.5; }
                            .form { display: flex; flex-direction: column; gap: 12px; }
                            input[type=text], input[type=email] {
                                padding: 12px 16px;
                                border-radius: 10px;
                                border: none;
                                font-size: 15px;
                                outline: none;
                                width: 100%%;
                            }
                            /* Werbe-Checkbox: gut sicht- und bedienbar, aber unaufdringlich */
                            .consent {
                                display: flex;
                                gap: 10px;
                                align-items: flex-start;
                                font-size: 13px;
                                opacity: 0.95;
                                padding: 10px 4px;
                                line-height: 1.45;
                                cursor: pointer;
                            }
                            .consent input { width: 18px; height: 18px; margin-top: 2px; flex-shrink: 0; }
                            .btn {
                                padding: 14px;
                                border-radius: 12px;
                                border: none;
                                background: white;
                                color: %s;
                                font-size: 15px;
                                font-weight: 600;
                                cursor: pointer;
                            }
                            .error {
                                background: rgba(255,0,0,0.2);
                                padding: 10px;
                                border-radius: 8px;
                                font-size: 13px;
                                text-align: center;
                                display: none;
                            }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <div class="header">
                                %s
                                <div>
                                    <div class="shop-name">%s</div>
                                    <div class="card-name">%s</div>
                                </div>
                            </div>
                            <div class="info">
                                <div class="info-title">🎉 Stempelkarte holen!</div>
                                <div class="info-text">
                                    Trag deinen Namen und deine E-Mail ein und bekomme
                                    die Stempelkarte direkt in deine Wallet!
                                </div>
                            </div>
                            <div class="error" id="error"></div>
                            <div class="form">
                                <input type="text" id="name" placeholder="Dein Name" required />
                                <input type="email" id="email" placeholder="Deine E-Mail" required />
                                <label class="consent">
                                    <input type="checkbox" id="consent" />
                                    <span>Ich möchte Angebote von %s per E-Mail erhalten.
                                          Abmeldung jederzeit über den Link in jeder Mail möglich.</span>
                                </label>
                                <button class="btn" onclick="getCard()">
                                    Karte holen 🎴
                                </button>
                            </div>
                        </div>
                        <script>
                            async function getCard() {
                                const name = document.getElementById('name').value.trim()
                                const email = document.getElementById('email').value.trim()
                                const consent = document.getElementById('consent').checked
                                const errorEl = document.getElementById('error')

                                if (!name || !email) {
                                    errorEl.style.display = 'block'
                                    errorEl.textContent = 'Bitte Name und E-Mail eingeben'
                                    return
                                }

                                try {
                                    const res = await fetch('/karte-neu/register', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({
                                            name, email,
                                            cardId: '%s',
                                            marketingConsent: consent
                                        })
                                    })
                                    if (!res.ok) throw new Error('register failed')
                                    const data = await res.json()
                                    window.location.href = '/karte/' + data.customerId + '/' + data.cardId
                                } catch(e) {
                                    errorEl.style.display = 'block'
                                    errorEl.textContent = 'Fehler — bitte nochmal versuchen'
                                }
                            }
                        </script>
                    </body>
                    </html>
                    """.formatted(
                    shopName, bgColor, bgColor,
                    logoUrl.isBlank() ? "" : "<img src='" + logoUrl + "' class='logo' alt='Logo'>",
                    shopName, card.getName(),
                    shopName,
                    cardId
            );

            return ResponseEntity.ok(html);
        } catch (Exception e) {
            return ResponseEntity.ok("<html><body style='font-family:sans-serif;padding:40px;text-align:center'><h2>Karte nicht gefunden</h2></body></html>");
        }
    }
}