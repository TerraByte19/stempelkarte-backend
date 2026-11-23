package com.example.stemplekarte.controller;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.config.KundenLink;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.service.KundenCookie;
import com.example.stemplekarte.service.QrPayload;
import com.example.stemplekarte.service.StandortPflicht;
import com.example.stemplekarte.wallet.WalletNotifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Stempeln per NFC-Karte am Tresen.
 *
 * <p>Ablauf: Das Personal haelt die NFC-Karte des Ladens ans Kundenhandy.
 * Das Handy liest die URL {@code /t/<token>} und oeffnet sie, nachdem der
 * Kunde den Hinweis antippt - ohne dieses Antippen ruft iOS die Adresse
 * nicht auf, stiller Aufruf ist am Handy nicht vorgesehen. Dann faellt der
 * Stempel, der Wallet-Pass bekommt seinen Push, und der Kunde sieht eine
 * Bestaetigungsseite.
 *
 * <p>Die Rollen sind vertauscht gegenueber dem QR-Weg: Dort weist sich das
 * Personal mit dem Staff-Token aus und der Kunde liefert seine ID im QR.
 * Hier traegt die NFC-Karte den Laden, und der Kunde weist sich mit dem
 * signierten Cookie aus, das beim Oeffnen seiner Kartenseite gesetzt wurde.
 *
 * <p><b>Offene Flanke, solange einfache Tags im Einsatz sind:</b> Ein
 * NTAG213 traegt eine feste URL. Wer sie einmal gesehen hat, kann sie
 * speichern und sich von zu Hause stempeln. Dagegen hilft nur ein Tag mit
 * wechselnder URL (NTAG 424 DNA, SUN/SDM) - die Pruefung dafuer kommt in
 * diesen Controller, sobald die Karten da sind. Bis dahin halten nur die
 * Abklingzeit und das Logbuch dagegen.
 */
@RestController
public class NfcController {

    private static final Logger log = LoggerFactory.getLogger(NfcController.class);

    private final CardRepository cardRepo;
    private final CustomerService customerService;
    private final WalletNotifier notifier;
    private final ScanLogRepository scanLogRepo;
    private final AppProperties props;

    // NFC-Karten je Filiale (Tarif Kette). Optional: ohne sie (Tests, aelterer
    // Aufbau) gibt es nur den einen Link je Stempelkarte.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.stemplekarte.repository.NfcTagRepository nfcTags;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.stemplekarte.repository.ShopOrtRepository orte;

    public NfcController(CardRepository cardRepo, CustomerService customerService,
                         WalletNotifier notifier, ScanLogRepository scanLogRepo,
                         AppProperties props) {
        this.cardRepo = cardRepo;
        this.customerService = customerService;
        this.notifier = notifier;
        this.scanLogRepo = scanLogRepo;
        this.props = props;
    }

    @GetMapping(value = "/t/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> tippen(@PathVariable String token,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        // Ohne eigenen Geheimwert (NFC_COOKIE_SECRET) bleibt NFC aus: mit
        // einem bekannten Vorgabewert koennte jeder ein Kunden-Cookie faelschen.
        if (props.nfc() == null || !props.nfc().enabled()
                || props.nfc().cookieSecret() == null || props.nfc().cookieSecret().isBlank()) {
            return seite("NFC ist abgeschaltet", "Bitte den QR-Code benutzen.", null, false);
        }

        Optional<Card> gefunden = cardRepo.findByNfcToken(token);
        // Kein Link einer Stempelkarte: dann vielleicht die NFC-Karte einer Filiale.
        com.example.stemplekarte.model.NfcTag tag = null;
        if (gefunden.isEmpty() && nfcTags != null) {
            tag = nfcTags.findByToken(token).orElse(null);
            if (tag != null) gefunden = cardRepo.findById(tag.getCardId());
        }
        if (gefunden.isEmpty()) {
            log.warn("[NFC] unbekannter Token: {}", kuerzen(token));
            return seite("Karte unbekannt", "Diese NFC-Karte gehört zu keinem Laden.", null, false);
        }

        Card card = gefunden.get();
        Shop shop = card.getShop();

        if (card.isPoints()) {
            return seite("Geht hier nicht", "Punktekarten brauchen den Betrag - bitte QR scannen.",
                    shop, false);
        }
        // Mitglieds- und 10er-Karte kamen nach dem NFC-Weg dazu. Bei der
        // 10er-Karte wuerde sich der Kunde selbst einen Besuch abbuchen -
        // bis das eigens gebaut ist, bleibt es beim QR-Scan.
        if (card.isMember() || card.isPackage()) {
            return seite("Geht hier nicht", "Bitte zeig deine Karte zum Scannen.", shop, false);
        }
        if (!card.isActive() || !shop.isActive()) {
            return seite("Karte nicht aktiv", "Dieser Laden stempelt gerade nicht.", shop, false);
        }
        // Testphase vorbei: der Scanner ist gesperrt, also auch dieser Weg.
        if (com.example.stemplekarte.service.TarifPruefung.testVorbei(shop, Instant.now())) {
            log.warn("[NFC] GESPERRT laden={} grund=testphase_vorbei", shop.getId());
            return seite("Stempeln gesperrt", "Dieser Laden stempelt gerade nicht.", shop, false);
        }
        if (!StandortPflicht.darfScannen(shop, Instant.now())) {
            log.warn("[NFC] GESPERRT laden={} grund=kein_standort", shop.getId());
            return seite("Stempeln gesperrt", StandortPflicht.sperrText(), shop, false);
        }

        // Filiale und Name der NFC-Karte fuers Protokoll. Der eine Link je
        // Stempelkarte kennt beides nicht: er zaehlt als Hauptstandort.
        String ortId = tag != null ? tag.getOrtId() : null;
        String nfcLabel = tag != null ? "NFC: " + tag.getLabel() : null;
        if (tag != null && tag.isGesperrt()) {
            log.warn("[NFC] GESPERRT laden={} grund=nfc_karte_gesperrt tag={}", shop.getId(), tag.getId());
            return seite("NFC-Karte gesperrt", "Bitte zeig deine Karte zum Scannen.", shop, false);
        }
        // Kette: dieselben Regeln wie am Scanner - die Karte muss in der
        // Filiale gelten, und die Filiale muss eingeschaltet sein.
        if (com.example.stemplekarte.service.OrteService.kette(shop)) {
            if (!card.giltIn(ortId)) {
                log.warn("[NFC] GESPERRT laden={} grund=karte_gilt_nicht ort={}", shop.getId(), ortId);
                return seite("Gilt hier nicht",
                        com.example.stemplekarte.service.KartenGeltung.GILT_NICHT, shop, false);
            }
            boolean filialeAn = ortId == null
                    ? shop.isHauptIstFiliale()
                    : orte != null && orte.findById(ortId)
                            .map(com.example.stemplekarte.model.ShopOrt::isAktiv).orElse(false);
            if (!filialeAn) {
                log.warn("[NFC] GESPERRT laden={} grund=filiale_aus ort={}", shop.getId(), ortId);
                return seite("Stempeln gesperrt", "Diese Filiale stempelt gerade nicht.", shop, false);
            }
        }

        // Wer ist das Handy? Ohne Cookie kennt der Server den Kunden nicht -
        // die NFC-Karte sagt nur, in welchem Laden getippt wurde.
        Optional<String> kunde = KundenCookie.lesen(request, card.getId(), props.nfc().cookieSecret());
        if (kunde.isEmpty()) {
            // Das Cookie gilt nur fuer die Adresse, die es gesetzt hat. Kommt
            // ein alter Tag ueber die Serveradresse, liegt es bei Kunden, die
            // sich ueber die Kundenadresse angemeldet haben, dort - also
            // einmal hinueber. Auf der Kundenadresse selbst nie (Schleife).
            // "weiter=1" markiert die Weiterleitung: reicht der Hoster den
            // Hostnamen anders durch als erwartet, bleibt es bei einem Sprung.
            if (!KundenLink.istKundenHost(request.getServerName())
                    && request.getParameter("weiter") == null) {
                log.info("[NFC] kein Kunden-Cookie auf {} - weiter zur Kundenadresse, laden={} karte={}",
                        request.getServerName(), shop.getId(), card.getId());
                return ResponseEntity.status(302)
                        .header(HttpHeaders.LOCATION, KundenLink.nfc(props.baseUrl(), token) + "?weiter=1")
                        .build();
            }
            log.info("[NFC] kein Kunden-Cookie - Noch keine Karte, laden={} karte={} host={}",
                    shop.getId(), card.getId(), request.getServerName());
            return seite("Noch keine Karte",
                    "Leg kurz deine Treuekarte an, dann zählt jedes Antippen.",
                    shop, false, anlegenKnopf(card));
        }
        String customerId = kunde.get();

        CustomerCard cc;
        try {
            cc = customerService.getOrCreateCustomerCard(customerId, card.getId());
        } catch (Exception e) {
            // Kunde geloescht (DSGVO) oder Cookie aus einer alten Datenbank:
            // das Cookie zeigt ins Leere und muss weg, sonst laeuft der Kunde
            // bei jedem Antippen in denselben Fehler.
            log.warn("[NFC] Cookie zeigt auf unbekannten Kunden {} - wird geloescht", customerId);
            KundenCookie.loeschen(response, card.getId());
            return seite("Noch keine Karte",
                    "Leg kurz deine Treuekarte an, dann zählt jedes Antippen.",
                    shop, false, anlegenKnopf(card));
        }

        // Abklingzeit: zweimal hintereinander antippen gibt sonst zwei
        // Stempel - weder das Personal noch der Kunde merkt den Unterschied
        // zum ersten Antippen.
        Optional<Instant> letzter = scanLogRepo
                .findFirstByCustomerIdAndCardIdOrderByScannedAtDesc(customerId, card.getId())
                .map(sl -> sl.getScannedAt());
        int abklingSekunden = props.nfc().cooldownSeconds();
        if (letzter.isPresent()
                && Duration.between(letzter.get(), Instant.now()).getSeconds() < abklingSekunden) {
            log.info("[NFC] Abklingzeit kunde={} karte={} laden={}",
                    customerId, card.getId(), shop.getId());
            return seite("Schon gestempelt",
                    "Du hast deinen Stempel schon bekommen. Der nächste geht beim nächsten Besuch.",
                    shop, true);
        }

        // Ohne NFC-Karte einer Filiale unveraendert der alte Aufruf.
        ScanResult result = tag == null
                ? customerService.processScan(new QrPayload(customerId, card.getId()), shop, 1)
                : customerService.stempelnPerNfc(new QrPayload(customerId, card.getId()), shop, 1, nfcLabel, ortId);
        cc = result.customerCard();

        log.info("[WALLET] NFC serial={} kunde={} karte={} laden={} stempelNeu={}",
                cc.getId(), customerId, card.getId(), shop.getId(), cc.getStamps());

        // Push an Wallet, SSE an eine offene Kartenseite. Abgesichert im
        // Notifier - der Stempel steht hier laengst in der Datenbank.
        notifier.nachStempelAenderung(cc);

        boolean voll = result.rewardsEarnedThisScan() > 0;
        if (voll) {
            // Apple meldet das ueber changeMessage im Pass von selbst,
            // Google braucht den ausdruecklichen Anstoss.
            notifier.googleKarteVoll(cc.getId(), card.getRewardText());
        }

        String titel = voll ? "Karte voll!" : "Stempel erhalten";
        String text = voll
                ? card.getRewardText() + " wartet auf dich."
                // Der Stand steht dabei, damit auffaellt, wenn das Handy einer
                // anderen Karte zugeordnet ist als der in der Wallet.
                : "Du hast jetzt " + cc.getStamps() + " von " + card.getRewardThreshold()
                        + " Stempeln. Steht schon in deiner Wallet.";
        // War die Karte schon voll, loest das Antippen die Praemie ein (wie
        // der QR-Scan). Das muss auf dem Bildschirm stehen - sonst sieht es
        // nach einem gewoehnlichen Stempel aus und die Praemie ist weg.
        if (result instanceof ScanResult.Redeemed) {
            titel = "Prämie eingelöst";
            text = card.getRewardText() + " - bitte dem Personal zeigen.";
        }

        return seite(titel, text, shop, true);
    }

    private String anlegenKnopf(Card card) {
        // Kundenadresse, nicht Serveradresse: dort entsteht beim Anmelden das
        // Cookie, das der Tag beim naechsten Antippen lesen muss.
        return "<a class='knopf' href='" + KundenLink.anmeldung(props.baseUrl(), card.getId())
                + "'>Karte anlegen</a>";
    }

    private ResponseEntity<String> seite(String titel, String text, Shop shop, boolean erfolg) {
        return seite(titel, text, shop, erfolg, "");
    }

    /**
     * Eine Seite, eine Aussage. Bewusst ohne Stempelstand und ohne Knopf zur
     * Wallet: der Pass meldet sich selbst, und je weniger hier steht, desto
     * schneller ist der Kunde fertig und das Personal mit dem naechsten dran.
     */
    private ResponseEntity<String> seite(String titel, String text, Shop shop,
                                         boolean erfolg, String extra) {
        // Die Ladenfarbe landet roh in einer CSS-Regel. Sie wird nirgends
        // geprueft, wenn der Laden sie setzt - "red;}body{opacity:0" reicht,
        // um die Seite seiner Kunden umzubauen. safeCssColor haelt das ab und
        // steht schon auf der Kartenseite (KartenseiteEscapingTest).
        String bg = LandingController.safeCssColor(
                shop != null ? shop.getColorBackground() : null);
        String ladenName = shop != null ? LandingController.escapeHtml(shop.getName()) : "";
        String logo = shop != null && shop.getLogoUrl() != null && !shop.getLogoUrl().isBlank()
                ? "<img class='logo' src='" + LandingController.escapeHtml(shop.getLogoUrl()) + "' alt=''>"
                : "";
        String zeichen = erfolg ? "✓" : "!";

        String html = """
                <!DOCTYPE html>
                <html lang="de">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                            background: %s;
                            min-height: 100vh;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            padding: 24px;
                            color: white;
                            text-align: center;
                        }
                        .box { max-width: 340px; width: 100%%; }
                        .zeichen {
                            width: 96px; height: 96px;
                            margin: 0 auto 24px;
                            border-radius: 50%%;
                            background: rgba(255,255,255,0.18);
                            display: flex; align-items: center; justify-content: center;
                            font-size: 52px; line-height: 1;
                            animation: pop 0.35s ease-out;
                        }
                        @keyframes pop {
                            from { transform: scale(0.4); opacity: 0; }
                            to   { transform: scale(1);   opacity: 1; }
                        }
                        h1 { font-size: 26px; font-weight: 600; margin-bottom: 10px; }
                        p  { font-size: 16px; opacity: 0.85; line-height: 1.45; }
                        .logo {
                            width: 44px; height: 44px; border-radius: 10px;
                            object-fit: cover; margin-bottom: 14px;
                            background: rgba(255,255,255,0.2);
                        }
                        .laden { font-size: 14px; opacity: 0.7; margin-top: 28px; }
                        .knopf {
                            display: inline-block; margin-top: 22px;
                            padding: 13px 26px; border-radius: 12px;
                            background: white; color: %s;
                            font-size: 16px; font-weight: 600; text-decoration: none;
                        }
                    </style>
                </head>
                <body>
                    <div class="box">
                        %s
                        <div class="zeichen">%s</div>
                        <h1>%s</h1>
                        <p>%s</p>
                        %s
                        <div class="laden">%s</div>
                    </div>
                </body>
                </html>
                """.formatted(LandingController.escapeHtml(titel), bg, bg, logo, zeichen,
                LandingController.escapeHtml(titel), LandingController.escapeHtml(text), extra, ladenName);

        // Wie bei der Kartenseite: Safari hat hier schon einmal eine alte
        // Antwort aus dem Cache geliefert (siehe LandingController).
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    /** Unbekannte Token nur angedeutet protokollieren - sie sind Zugangsdaten. */
    private static String kuerzen(String token) {
        if (token == null) return "null";
        return token.length() <= 6 ? "***" : token.substring(0, 6) + "...";
    }
}
