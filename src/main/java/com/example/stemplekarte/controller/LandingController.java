package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.service.PointsMath;
import com.example.stemplekarte.service.RewardService;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class LandingController {

    private static final Logger log = LoggerFactory.getLogger(LandingController.class);

    /** Baut aus einem Text einen sicheren JS-String-Literal (inkl. Anfuehrungszeichen). */
    private static String toJsString(String s) {
        if (s == null) return "\"\"";
        String escaped = s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("<", "\\u003c");
        return "\"" + escaped + "\"";
    }

    private final CustomerService customerService;
    private final CardService cardService;
    private final GoogleWalletService googleWalletService;
    private final RewardService rewardService;

    @Value("${stempelkarte.base-url:http://localhost:8080}")
    private String baseUrl;

    public LandingController(CustomerService customerService, CardService cardService,
                             GoogleWalletService googleWalletService,
                             RewardService rewardService) {
        this.customerService = customerService;
        this.cardService = cardService;
        this.googleWalletService = googleWalletService;
        this.rewardService = rewardService;
    }

    // ── Karten-Anmeldung mit Werbe-Einwilligung (von /karte-neu/ aufgerufen) ──
    // Ersetzt den alten Aufruf POST /api/customer, weil wir hier zusätzlich
    // - die Karte direkt verknüpfen
    // - die Marketing-Einwilligung pro Karte speichern
    // - die Bestätigungs-Mail (Double-Opt-In) auslösen.
    public record RegisterRequest(String name, String email, String cardId,
                                  boolean marketingConsent) {}

    @PostMapping("/karte-neu/register")
    public Map<String, Object> registerForCard(@RequestBody RegisterRequest req) {
        customerService.registerForCard(
                req.name(), req.email(), req.cardId(), req.marketingConsent());
        // Jede Anmeldung verlangt jetzt IMMER eine Mail-Bestätigung.
        // Das Frontend zeigt daher immer den "Check deine Mails"-Screen;
        // die Weiterleitung zur Karte passiert erst über den Link in der Mail.
        return Map.of("status", "confirmation_sent");
    }

    // Bestehende Kunden-Landing-Page (mit Stempeln)
    /**
     * Kundenseite einer Punktekarte.
     *
     * Zeigt Stand, naechstes Ziel und darunter den ganzen Katalog mit
     * Fortschrittsbalken - dieselbe Aufteilung wie auf der Wallet-Karte,
     * damit der Kunde nicht zwei Darstellungen derselben Sache lernen muss.
     *
     * Deutsch, wie die Stempelseite: das Backend hat noch keine
     * Uebersetzungstabelle (siehe docs/specs/2026-08-30-arabisch-rtl-...).
     *
     * Das CSS ist bewusst eine eigene Kopie und nicht mit der Stempelseite
     * geteilt. Geteilt haette geheissen, die Stempelseite anzufassen, und die
     * liegt gerade auf echten Handys. Wenn beide Seiten sich eingespielt
     * haben, lohnt das Zusammenlegen - vorher nicht.
     */
    private ResponseEntity<String> punkteSeite(CustomerCard cc, Card card, Customer customer,
                                               String customerId, String cardId) {
        String shopName = card.getShop().getName();
        String bgColor = card.getShop().getColorBackground();
        String logoUrl = card.getShop().getLogoUrl() != null ? card.getShop().getLogoUrl() : "";

        String googleSaveUrl = "";
        try {
            googleSaveUrl = googleWalletService.generateSaveUrl(cc);
        } catch (Exception e) {
            // Google Wallet nicht konfiguriert
        }

        String applePassUrl = baseUrl + "/api/customer/" + customerId
                + "/card/" + cardId + "/apple-pass";

        long stand = cc.getPointsX100();
        List<Reward> katalog = rewardService.list(card);
        Reward ziel = RewardService.naechstesZiel(katalog, stand);

        String zielZeile = zielZeileText(ziel, stand);
        String katalogHtml = katalogHtml(katalog, stand);

        String html = """
                <!DOCTYPE html>
                <html lang="de">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s \u2014 Punktekarte</title>
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
                        .shop-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
                        .shop-logo {
                            width: 50px; height: 50px; border-radius: 12px;
                            object-fit: cover; background: rgba(255,255,255,0.2);
                        }
                        .shop-name { font-size: 20px; font-weight: 600; }
                        .card-name { font-size: 13px; opacity: 0.75; margin-top: 2px; }
                        .customer-name { font-size: 14px; opacity: 0.8; margin-bottom: 20px; }
                        .stand-label {
                            font-size: 13px; opacity: 0.75; text-transform: uppercase;
                            letter-spacing: 0.5px;
                        }
                        .stand { font-size: 44px; font-weight: 800; line-height: 1.1; margin: 2px 0 4px; }
                        .ziel { font-size: 14px; opacity: 0.9; margin-bottom: 22px; }
                        .katalog-titel {
                            font-size: 12px; opacity: 0.7; text-transform: uppercase;
                            letter-spacing: 0.5px; margin-bottom: 10px;
                        }
                        .reward {
                            background: rgba(255,255,255,0.12);
                            border-radius: 12px; padding: 12px; margin-bottom: 8px;
                        }
                        .reward.ready { background: rgba(255,255,255,0.28); }
                        .reward-head {
                            display: flex; justify-content: space-between;
                            align-items: baseline; gap: 8px; margin-bottom: 8px;
                        }
                        .reward-name { font-size: 15px; font-weight: 600; }
                        .reward-cost { font-size: 13px; opacity: 0.85; }
                        .bar { height: 6px; border-radius: 3px; background: rgba(255,255,255,0.25); overflow: hidden; }
                        .bar-fill { height: 100%%; background: rgba(255,255,255,0.95); border-radius: 3px; }
                        .leer {
                            font-size: 14px; opacity: 0.85; padding: 12px;
                            background: rgba(255,255,255,0.15); border-radius: 10px;
                        }
                        .wallet-buttons { display: flex; flex-direction: column; gap: 12px; margin-top: 24px; }
                        .btn-apple {
                            display: block; background: black; color: white; text-decoration: none;
                            padding: 14px; border-radius: 12px; text-align: center;
                            font-size: 15px; font-weight: 500;
                        }
                        .btn-google {
                            display: block; background: white; color: #333; text-decoration: none;
                            padding: 14px; border-radius: 12px; text-align: center;
                            font-size: 15px; font-weight: 500;
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
                        <div class="customer-name">\uD83D\uDC64 %s</div>
                        <div class="stand-label">Punktestand</div>
                        <div class="stand" id="stand">%s</div>
                        <div class="ziel" id="ziel">%s</div>
                        <div class="katalog-titel">Pr\u00e4mien</div>
                        <div id="katalog">%s</div>
                        <div class="wallet-buttons">
                            <a href="%s" class="btn-apple" id="apple-btn">
                                \uD83C\uDF4E Zu Apple Wallet hinzuf\u00fcgen
                            </a>
                            %s
                        </div>
                    </div>
                    <script>
                        // Live-Aktualisierung OHNE Seiten-Reload - genau wie auf der
                        // Stempelseite. Ein location.reload() holte auf iOS Safari
                        // zeitweise die alte Seite aus dem Cache, und der Stand blieb
                        // stehen. Deshalb wird hier nur der DOM ausgetauscht.
                        const custId = %s;
                        const cId = %s;

                        function zeichne(d) {
                            var stand = document.getElementById('stand');
                            if (stand && d.pointsText) stand.textContent = d.pointsText;
                            var ziel = document.getElementById('ziel');
                            if (ziel) {
                                ziel.textContent = d.zielName
                                    ? ('N\u00e4chste Pr\u00e4mie: ' + d.zielName + ' - noch ' + d.fehlendText)
                                    : 'Noch keine Pr\u00e4mie hinterlegt';
                            }
                            if (Array.isArray(d.katalog)) {
                                // Aufbau ueber die DOM-Schnittstelle, NICHT ueber
                                // innerHTML: Praemiennamen kommen vom Laden, und
                                // mit innerHTML waere jeder Name ausfuehrbares
                                // HTML auf der Kartenseite jedes seiner Kunden.
                                var k = document.getElementById('katalog');
                                if (k) {
                                    var frag = document.createDocumentFragment();
                                    for (var i = 0; i < d.katalog.length; i++) {
                                        var r = d.katalog[i];
                                        var pct = r.costPointsX100 > 0
                                            ? Math.min(100, Math.round(d.pointsX100 * 100 / r.costPointsX100))
                                            : 100;

                                        var box = document.createElement('div');
                                        box.className = 'reward' + (r.bezahlbar ? ' ready' : '');

                                        var head = document.createElement('div');
                                        head.className = 'reward-head';
                                        var name = document.createElement('span');
                                        name.className = 'reward-name';
                                        name.textContent = r.name;
                                        var cost = document.createElement('span');
                                        cost.className = 'reward-cost';
                                        cost.textContent = r.costText + ' Punkte';
                                        head.appendChild(name);
                                        head.appendChild(cost);

                                        var bar = document.createElement('div');
                                        bar.className = 'bar';
                                        var fill = document.createElement('div');
                                        fill.className = 'bar-fill';
                                        fill.style.width = pct + '%%';
                                        bar.appendChild(fill);

                                        box.appendChild(head);
                                        box.appendChild(bar);
                                        frag.appendChild(box);
                                    }
                                    k.replaceChildren(frag);
                                }
                            }
                        }

                        async function check() {
                            try {
                                const url = '/api/customer/' + custId + '/card/' + cId + '/points';
                                const res = await fetch(url, { cache: 'no-store' });
                                if (!res.ok) return;
                                zeichne(await res.json());
                            } catch (e) {}
                        }

                        // Live-Push per SSE. Faellt der Stream aus, laeuft das Polling weiter.
                        try {
                            var es = new EventSource('/api/customer/' + custId + '/card/' + cId + '/stream');
                            es.addEventListener('points', function (ev) {
                                try { check(); } catch (e) {}
                            });
                        } catch (e) {}

                        setInterval(check, 3000);
                        document.addEventListener('visibilitychange', function () { if (!document.hidden) check(); });
                        window.addEventListener('pageshow', check);
                        window.addEventListener('online', check);

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
                escapeHtml(shopName), safeCssColor(bgColor),
                // Logo-URL steht in einem Attribut mit einfachen
                // Anfuehrungszeichen - ein Apostroph darin bricht daraus aus.
                // escapeHtml ersetzt ihn durch &#39;.
                logoUrl.isBlank() ? ""
                        : "<img src='" + escapeHtml(logoUrl) + "' class='shop-logo' alt='Logo'>",
                escapeHtml(shopName), escapeHtml(card.getName()),
                escapeHtml(customer.getName()),
                PointsMath.formatiere(stand),
                zielZeile,
                katalogHtml,
                escapeHtml(applePassUrl),
                googleSaveUrl.isBlank() ? ""
                        : "<a href='" + escapeHtml(googleSaveUrl) + "' class='btn-google' id='google-btn'>" +
                                "\uD83E\uDD16 Zu Google Wallet hinzuf\u00fcgen</a>",
                // Die beiden IDs stehen in JS-Zeichenketten. toJsString
                // liefert sie inklusive Anfuehrungszeichen - im Vorlagentext
                // stehen an dieser Stelle deshalb KEINE.
                toJsString(customerId), toJsString(cardId)
        );

        log.info("Landing-Punktekarte geladen: customerCard={} card={} punkte={}",
                cc.getId(), cardId, stand);

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(html);
    }

    /** Die Zeile unter dem Punktestand. Ohne Praemie steht dort kein
     *  erfundenes Ziel, sondern der ehrliche Hinweis. */
    private String zielZeileText(Reward ziel, long stand) {
        if (ziel == null) return "Noch keine Pr\u00e4mie hinterlegt";
        long fehlend = Math.max(0, ziel.getCostPointsX100() - stand);
        // Der Name kommt vom Laden und landet roh im HTML. Im Katalog
        // darunter wird er escaped - hier fehlte es, und genau diese
        // Asymmetrie ist die Luecke.
        String name = escapeHtml(ziel.getName());
        return fehlend == 0
                ? name + " ist bereit!"
                : "N\u00e4chste Pr\u00e4mie: " + name
                        + " - noch " + PointsMath.formatiere(fehlend);
    }

    /** Katalog mit Fortschrittsbalken. Der Balken zeigt, wie weit der Stand
     *  auf die jeweilige Praemie zugelaufen ist. */
    private String katalogHtml(List<Reward> katalog, long stand) {
        if (katalog.isEmpty()) {
            return "<div class='leer'>Dieser Laden hat noch keine Pr\u00e4mien "
                    + "hinterlegt. Deine Punkte sammeln sich trotzdem.</div>";
        }
        StringBuilder sb = new StringBuilder();
        for (Reward r : katalog) {
            boolean bezahlbar = r.getCostPointsX100() <= stand;
            long prozent = r.getCostPointsX100() > 0
                    ? Math.min(100, stand * 100 / r.getCostPointsX100())
                    : 100;
            sb.append("<div class='reward")
              .append(bezahlbar ? " ready" : "")
              .append("'><div class='reward-head'><span class='reward-name'>")
              .append(escapeHtml(r.getName()))
              .append("</span><span class='reward-cost'>")
              .append(PointsMath.formatiere(r.getCostPointsX100()))
              .append(" Punkte</span></div><div class='bar'><div class='bar-fill' style='width:")
              .append(prozent)
              .append("%'></div></div></div>");
        }
        return sb.toString();
    }

    /** Praemiennamen kommen vom Laden und landen roh im HTML. */
    /**
     * Eine Farbe, die gefahrlos in eine CSS-Regel darf.
     *
     * escapeHtml reicht hier NICHT: es ersetzt spitze Klammern und
     * Anfuehrungszeichen, aber im CSS-Kontext braucht es Semikolon und
     * geschweifte Klammern. Die Ladenfarbe wird nirgends validiert -
     * ein Laden koennte sie auf "red;}body{opacity:0" setzen und damit
     * die Kartenseite seiner Kunden umgestalten.
     *
     * Deshalb keine Filterung, sondern eine Pruefung: entweder es ist
     * eine Hex-Farbe, oder es gilt der Standardwert.
     */
    static String safeCssColor(String c) {
        return (c != null && c.matches("#[0-9A-Fa-f]{3,8}")) ? c : "#3C3489";
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    @GetMapping(value = "/karte/{customerId}/{cardId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> landingPage(@PathVariable String customerId,
                                              @PathVariable String cardId) {
        try {
            CustomerCard cc = customerService.getOrCreateCustomerCard(customerId, cardId);
            Card card = cc.getCard();
            Customer customer = cc.getCustomer();

            // Punktekarten haben eine eigene Seite. Der Stempel-Weg darunter
            // bleibt unangetastet - er laeuft in echten Laeden.
            if (card.isPoints()) {
                return punkteSeite(cc, card, customer, customerId, cardId);
            }

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
            // Fuer die Anzeige gedeckelt: hat der Laden die Stempelzahl
            // nachtraeglich gesenkt, liegt der echte Stand ueber der Schwelle -
            // "10 von 5" waere verwirrend. Der rohe Stand geht trotzdem ins JS,
            // damit der ?shown=-Abgleich weiter mit dem Server-Stand vergleicht.
            int stampsShown = Math.min(stamps, threshold);

            StringBuilder stampsHtml = new StringBuilder();
            for (int i = 1; i <= threshold; i++) {
                if (i <= stampsShown) {
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
                            // Live-Aktualisierung OHNE Seiten-Reload: der Zaehler wird direkt
                            // im DOM ersetzt. So haengt nichts mehr am HTML-Cache des Handys
                            // (frueher: location.reload() konnte die alte Seite aus dem Cache
                            // holen -> Zaehler blieb stehen / Reload-Schleife).
                            const custId = %s;
                            const cId = %s;
                            const threshold = %d;
                            const rewardText = %s;
                            let shownStamps = %d;

                            function renderStamps(n) {
                                // shownStamps bleibt der ROHE Server-Stand (fuer ?shown=),
                                // angezeigt wird hoechstens die Schwelle.
                                shownStamps = n;
                                var shown = Math.min(n, threshold);
                                var h = '';
                                for (var i = 1; i <= threshold; i++) {
                                    h += (i <= shown)
                                        ? "<div class='stamp filled'>☕</div>"
                                        : "<div class='stamp empty'>" + i + "</div>";
                                }
                                var grid = document.querySelector('.stamps-grid');
                                if (grid) grid.innerHTML = h;
                                var prog = document.querySelector('.progress');
                                if (prog) prog.textContent = shown + ' von ' + threshold + ' Stempeln';
                                var rw = document.querySelector('.reward-text');
                                if (rw) rw.textContent = (n >= threshold)
                                    ? '🎉 ' + rewardText + ' verfügbar!'
                                    : 'Noch ' + (threshold - n) + ' Stempel bis: ' + rewardText;
                            }

                            async function check() {
                                try {
                                    const url = '/api/customer/' + custId + '/card/' + cId + '?shown=' + shownStamps;
                                    const res = await fetch(url, { cache: 'no-store' });
                                    if (!res.ok) return;
                                    const data = await res.json();
                                    if (typeof data.stamps === 'number' && data.stamps !== shownStamps) {
                                        renderStamps(data.stamps);
                                    }
                                } catch (e) {}
                            }

                            // Live-Push per SSE: neuer Stempel erscheint sofort, ohne aufs
                            // Polling zu warten. Faellt der Stream aus, laeuft das Polling weiter.
                            try {
                                var es = new EventSource('/api/customer/' + custId + '/card/' + cId + '/stream');
                                es.addEventListener('stamps', function (ev) {
                                    try {
                                        var d = JSON.parse(ev.data);
                                        if (typeof d.stamps === 'number' && d.stamps !== shownStamps) renderStamps(d.stamps);
                                    } catch (e) {}
                                });
                            } catch (e) {}

                            setInterval(check, 3000);
                            document.addEventListener('visibilitychange', function () { if (!document.hidden) check(); });
                            window.addEventListener('pageshow', check);
                            window.addEventListener('online', check);

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
                    escapeHtml(shopName), safeCssColor(bgColor),
                    logoUrl.isBlank() ? ""
                            : "<img src='" + escapeHtml(logoUrl) + "' class='shop-logo' alt='Logo'>",
                    escapeHtml(shopName), escapeHtml(card.getName()),
                    escapeHtml(customer.getName()),
                    stampsShown, threshold,
                    stampsHtml,
                    stamps >= threshold ? "🎉 " + escapeHtml(card.getRewardText()) + " verfügbar!" :
                            "Noch " + (threshold - stamps) + " Stempel bis: " + escapeHtml(card.getRewardText()),
                    escapeHtml(applePassUrl),
                    googleSaveUrl.isBlank() ? ""
                            : "<a href='" + escapeHtml(googleSaveUrl) + "' class='btn-google' id='google-btn'>" +
                                    "🤖 Zu Google Wallet hinzufügen</a>",
                    // Parameter fuer das JS: custId, cId, threshold, rewardText (als JS-String), stamps
                    toJsString(customerId), toJsString(cardId), threshold,
                    toJsString(card.getRewardText()), stamps
            );

            log.info("Landing-Karte geladen: customerCard={} card={} stamps={} threshold={}",
                    cc.getId(), cardId, stamps, threshold);

            // no-cache (NICHT no-store): der Browser darf die Seite im
            // Back-Forward-Cache halten, muss sie aber vor Benutzung neu
            // pruefen. "no-store" verbietet den bfcache komplett -> auf dem
            // iPhone wird die Seite bei jedem App-Wechsel voll neu geladen ->
            // kurzes weisses Aufblitzen ("Design verschwindet, kommt wieder").
            // Ein veralteter Zaehler ist kein Problem mehr: renderStamps() +
            // der pageshow/visibilitychange-Handler ziehen den Stand live nach.
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(html);

        } catch (Exception e) {
            return ResponseEntity.ok("""
                    <html><body style="font-family:sans-serif;padding:40px;text-align:center">
                    <h2>Karte nicht gefunden</h2>
                    <p>%s</p>
                    </body></html>
                    """.formatted(escapeHtml(e.getMessage())));
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
                            .success {
                                display: none;
                                text-align: center;
                                padding: 8px 0 4px;
                            }
                            .success-icon { font-size: 48px; margin-bottom: 12px; }
                            .success-title { font-size: 18px; font-weight: 700; margin-bottom: 8px; }
                            .success-text { font-size: 14px; opacity: 0.9; line-height: 1.6; }
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
                            <div class="form" id="form">
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
                            <div class="success" id="success">
                                <div class="success-icon">📧</div>
                                <div class="success-title">Fast geschafft!</div>
                                <div class="success-text">
                                    Wir haben dir eine E-Mail geschickt.<br>
                                    Klick auf den Link darin, um deine Stempelkarte
                                    direkt aufs Handy zu bekommen.
                                </div>
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
                                            cardId: %s,
                                            marketingConsent: consent
                                        })
                                    })
                                    if (!res.ok) throw new Error('register failed')

                                    // Jede Anmeldung verlangt jetzt eine Mail-Bestätigung.
                                    // Immer den "Check deine Mails"-Screen zeigen — die
                                    // Weiterleitung zur Karte passiert über den Mail-Link.
                                    document.getElementById('form').style.display = 'none'
                                    document.getElementById('success').style.display = 'block'
                                } catch(e) {
                                    errorEl.style.display = 'block'
                                    errorEl.textContent = 'Fehler — bitte nochmal versuchen'
                                }
                            }
                        </script>
                    </body>
                    </html>
                    """.formatted(
                    escapeHtml(shopName), safeCssColor(bgColor), safeCssColor(bgColor),
                    logoUrl.isBlank() ? ""
                            : "<img src='" + escapeHtml(logoUrl) + "' class='logo' alt='Logo'>",
                    escapeHtml(shopName), escapeHtml(card.getName()),
                    escapeHtml(shopName),
                    // cardId steht in einer JS-Zeichenkette - toJsString
                    // liefert die Anfuehrungszeichen mit.
                    toJsString(cardId)
            );

            return ResponseEntity.ok(html);
        } catch (Exception e) {
            return ResponseEntity.ok("<html><body style='font-family:sans-serif;padding:40px;text-align:center'><h2>Karte nicht gefunden</h2></body></html>");
        }
    }
}