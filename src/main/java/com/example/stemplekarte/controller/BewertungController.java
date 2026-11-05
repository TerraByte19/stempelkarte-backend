package com.example.stemplekarte.controller;

import com.example.stemplekarte.i18n.Texts;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Rueckmeldung;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.service.RueckmeldungService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;

import static com.example.stemplekarte.controller.LandingController.escapeHtml;

/**
 * Bewertungsseite fuer Kunden: Sterne und ein Satz, privat an den Laden.
 * Offen wie die Kartenseite, aber ohne Anlegen - wer keine Karte hat,
 * bekommt "nicht gefunden".
 *
 * Der Google-Link steht NACH dem Abschicken fuer jeden da, egal wie viele
 * Sterne. Google verbietet, nur die Zufriedenen weiterzuleiten; wer das
 * tut, riskiert, dass Google Bewertungen loescht. Siehe Spec.
 */
@Tag(name = "Rueckmeldung", description = "Bewertungsseite fuer Kunden")
@RestController
public class BewertungController {

    private static final Logger log = LoggerFactory.getLogger(BewertungController.class);

    private final CustomerCardRepository customerCardRepo;
    private final RueckmeldungService service;

    public BewertungController(CustomerCardRepository customerCardRepo, RueckmeldungService service) {
        this.customerCardRepo = customerCardRepo;
        this.service = service;
    }

    @GetMapping(value = "/bewertung/{customerId}/{cardId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> seite(@PathVariable String customerId, @PathVariable String cardId) {
        CustomerCard cc = customerCardRepo.findByCustomer_IdAndCard_Id(customerId, cardId).orElse(null);
        if (cc == null) {
            return ResponseEntity.ok(hinweisSeite(Texts.STANDARD, Texts.t(Texts.STANDARD, "card.not_found"), ""));
        }
        Card card = cc.getCard();
        Shop shop = card.getShop();
        String lang = Texts.sprache(cc.getCustomer(), shop);
        String zurueck = "/karte/" + escapeHtml(customerId) + "/" + escapeHtml(cardId);
        if (!shop.isRueckmeldungAktiv()) {
            return ResponseEntity.ok(hinweisSeite(lang, Texts.t(lang, "review.unavailable"),
                    "<a href='" + zurueck + "'>" + Texts.t(lang, "review.back") + "</a>"));
        }
        String bgColor = LandingController.farbeVon(card);
        String logoUrl = LandingController.logoVon(card);
        String html = seiteHtml(lang, shop, cc, customerId, cardId, bgColor, logoUrl, zurueck);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(LandingController.mitKontrast(html, bgColor, shop.getNameColor()));
    }

    static String seiteHtml(Shop shop, CustomerCard cc, String customerId, String cardId,
                            String bgColor, String logoUrl, String zurueck) {
        return seiteHtml(Texts.STANDARD, shop, cc, customerId, cardId, bgColor, logoUrl, zurueck);
    }

    static String seiteHtml(String lang, Shop shop, CustomerCard cc, String customerId, String cardId,
                            String bgColor, String logoUrl, String zurueck) {
        StringBuilder sterne = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            sterne.append("<button type='button' class='stern' data-wert='").append(i)
                  .append("' aria-label='").append(escapeHtml(Texts.t(lang, "review.star_of", i))).append("'>★</button>");
        }
        return """
                <!DOCTYPE html>
                <html%s>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s — %s</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                            background: #f5f5f7; min-height: 100vh;
                            display: flex; align-items: center; justify-content: center; padding: 20px;
                        }
                        .card {
                            background: %s; border-radius: 20px; padding: 28px; width: 100%%; max-width: 380px;
                            color: rgb(var(--ink)); box-shadow: 0 20px 60px rgba(0,0,0,0.3);
                        }
                        .shop-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
                        .shop-logo { width: 50px; height: 50px; border-radius: 12px; object-fit: cover; background: rgba(var(--ink),0.2); }
                        .shop-name { font-size: 20px; font-weight: 600; }
                        h2 { font-size: 17px; font-weight: 600; margin-bottom: 16px; }
                        .sterne { display: flex; justify-content: center; gap: 6px; margin-bottom: 18px; }
                        .stern {
                            background: none; border: none; font-size: 40px; line-height: 1; cursor: pointer;
                            color: rgba(var(--ink),0.3); padding: 4px; transition: transform .1s;
                        }
                        .stern.an { color: #f5b301; }
                        .stern:active { transform: scale(1.15); }
                        textarea {
                            width: 100%%; min-height: 96px; border-radius: 12px; border: none; padding: 12px;
                            font: inherit; font-size: 15px; resize: vertical; margin-bottom: 14px;
                            background: rgba(var(--ink),0.12); color: rgb(var(--ink));
                        }
                        textarea::placeholder { color: rgba(var(--ink),0.55); }
                        .btn {
                            display: block; width: 100%%; padding: 14px; border-radius: 12px; border: none;
                            background: rgb(var(--ink)); color: rgb(var(--ink-gegen)); font-size: 16px; font-weight: 600;
                            cursor: pointer;
                        }
                        .btn[disabled] { opacity: .5; cursor: default; }
                        .hinweis { font-size: 13px; opacity: .8; margin-top: 12px; text-align: center; }
                        .fehler { color: #ffd0d0; background: rgba(120,0,0,0.35); padding: 10px; border-radius: 10px; font-size: 13px; margin-bottom: 12px; display: none; }
                        .hidden { display: none; }
                        .danke { text-align: center; }
                        .danke .gross { font-size: 40px; margin-bottom: 10px; }
                        .zurueck { display: block; margin-top: 18px; text-align: center; font-size: 14px; color: inherit; opacity: .85; }
                        .falle { position: absolute; left: -9999px; width: 1px; height: 1px; opacity: 0; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="shop-header">
                            %s
                            <div class="shop-name">%s</div>
                        </div>
                        <form id="formular" autocomplete="off">
                            <h2>%s</h2>
                            <div class="sterne" id="sterne">%s</div>
                            <textarea id="text" maxlength="%d" placeholder="%s"></textarea>
                            <input type="text" name="website" id="website" class="falle" tabindex="-1" autocomplete="off">
                            <div class="fehler" id="fehler"></div>
                            <button type="submit" class="btn" id="senden" disabled>%s</button>
                            <p class="hinweis">%s</p>
                        </form>
                        <div class="danke hidden" id="danke">
                            <div class="gross">🙏</div>
                            <h2 id="danke-text">%s</h2>
                            <p class="hinweis" id="danke-hinweis">%s</p>
                            %s
                        </div>
                        <a class="zurueck" href="%s">%s</a>
                    </div>
                    <script>
                        (function () {
                            var card = document.querySelector('.card');
                            var custId = %s, cardId = %s;
                            var tAlready = %s, tThanks = %s, tError = %s, tOffline = %s;
                            var wert = 0;
                            var sterne = Array.prototype.slice.call(document.querySelectorAll('.stern'));
                            var senden = document.getElementById('senden');
                            var fehler = document.getElementById('fehler');
                            function male() {
                                sterne.forEach(function (s) {
                                    s.classList.toggle('an', Number(s.getAttribute('data-wert')) <= wert);
                                });
                                senden.disabled = wert === 0;
                            }
                            sterne.forEach(function (s) {
                                s.addEventListener('click', function () { wert = Number(s.getAttribute('data-wert')); male(); });
                            });
                            function fertig(titel, hinweis) {
                                document.getElementById('formular').classList.add('hidden');
                                if (titel) document.getElementById('danke-text').textContent = titel;
                                if (hinweis) document.getElementById('danke-hinweis').textContent = hinweis;
                                document.getElementById('danke').classList.remove('hidden');
                            }
                            document.getElementById('formular').addEventListener('submit', async function (ev) {
                                ev.preventDefault();
                                if (wert === 0) return;
                                senden.disabled = true;
                                fehler.style.display = 'none';
                                try {
                                    var res = await fetch('/api/public/rueckmeldung', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({
                                            customerId: custId, cardId: cardId, sterne: wert,
                                            text: document.getElementById('text').value,
                                            website: document.getElementById('website').value
                                        })
                                    });
                                    if (res.ok) { fertig(null, null); return; }
                                    if (res.status === 409) { fertig(tThanks, tAlready); return; }
                                    var d = null;
                                    try { d = await res.json(); } catch (e) {}
                                    fehler.textContent = (d && d.error) ? d.error : tError;
                                    fehler.style.display = 'block';
                                    senden.disabled = false;
                                } catch (e) {
                                    fehler.textContent = tOffline;
                                    fehler.style.display = 'block';
                                    senden.disabled = false;
                                }
                            });
                        })();
                    </script>
                </body>
                </html>
                """.formatted(
                Texts.htmlAttrs(lang), escapeHtml(shop.getName()), Texts.t(lang, "review.title"), LandingController.safeCssColor(bgColor),
                logoUrl.isBlank() ? ""
                        : "<img src='" + escapeHtml(logoUrl) + "' class='shop-logo' alt='Logo'>",
                escapeHtml(shop.getName()),
                Texts.t(lang, "review.question", escapeHtml(cc.getCustomer().getName())),
                sterne.toString(),
                Rueckmeldung.TEXT_MAX, escapeHtml(Texts.t(lang, "review.placeholder")),
                Texts.t(lang, "review.send"),
                Texts.t(lang, "review.private_hint", escapeHtml(shop.getName())),
                Texts.t(lang, "review.thanks", escapeHtml(cc.getCustomer().getName())),
                Texts.t(lang, "review.received", escapeHtml(shop.getName())),
                LandingController.bewertungsLink(lang, shop),
                zurueck, Texts.t(lang, "review.back"),
                // IDs stehen in JS-Zeichenketten: toJsString liefert die
                // Anfuehrungszeichen mit.
                LandingController.toJsString(customerId), LandingController.toJsString(cardId),
                LandingController.toJsString(Texts.t(lang, "review.already")), LandingController.toJsString(Texts.t(lang, "review.thanks_short")),
                LandingController.toJsString(Texts.t(lang, "review.error")), LandingController.toJsString(Texts.t(lang, "review.offline")));
    }

    private static String hinweisSeite(String lang, String text, String linkHtml) {
        return "<html" + Texts.htmlAttrs(lang) + "><body style='font-family:sans-serif;padding:40px;text-align:center'><h2>"
                + escapeHtml(text) + "</h2><p style='margin-top:16px'>" + linkHtml + "</p></body></html>";
    }

    // ── Abgeben ──────────────────────────────────────────────────────────

    public record RueckmeldungRequest(String customerId, String cardId, Integer sterne,
                                      String text, String website) {}

    @PostMapping("/api/public/rueckmeldung")
    public ResponseEntity<Map<String, Object>> abgeben(@RequestBody RueckmeldungRequest req) {
        // Honigtopf wie beim Kontaktformular: nur Bots fuellen das Feld.
        if (req.website() != null && !req.website().isBlank()) {
            log.info("Rueckmeldung verworfen (Honigtopf)");
            return ResponseEntity.ok(Map.of("ok", true));
        }
        if (req.customerId() == null || req.customerId().isBlank()
                || req.cardId() == null || req.cardId().isBlank() || req.sterne() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Angaben fehlen"));
        }
        try {
            Rueckmeldung r = service.abgeben(req.customerId(), req.cardId(), req.sterne(), req.text(), Instant.now());
            log.info("Rueckmeldung {} Sterne fuer Shop {}", r.getSterne(), r.getShop().getId());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Karte nicht gefunden"));
        } catch (RueckmeldungService.Aus e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (RueckmeldungService.HeuteSchon e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
