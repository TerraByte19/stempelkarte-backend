package com.example.stemplekarte.wallet;

import com.example.stemplekarte.i18n.Texts;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.KartenFortschritt;
import com.example.stemplekarte.service.PointsMath;
import com.example.stemplekarte.service.RewardService;
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.PKLocation;
import de.brendamour.jpasskit.PKPass;
import de.brendamour.jpasskit.enums.PKBarcodeFormat;
import de.brendamour.jpasskit.enums.PKPassType;
import de.brendamour.jpasskit.passes.PKGenericPass;
import de.brendamour.jpasskit.passes.PKGenericPassBuilder;
import de.brendamour.jpasskit.signing.PKFileBasedSigningUtil;
import de.brendamour.jpasskit.signing.PKPassTemplateFolder;
import de.brendamour.jpasskit.signing.PKSigningInformation;
import de.brendamour.jpasskit.signing.PKSigningInformationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ApplePassService {

    private static final Logger log = LoggerFactory.getLogger(ApplePassService.class);

    private static final String B64_PATH = "/etc/secrets/pass-certificate.b64";
    private static final String P12_PATH = "/tmp/pass-certificate.p12";
    private static final String WWDR_PATH = "/etc/secrets/apple-wwdr.pem";

    private final AppProperties props;
    private final PassTemplateGenerator templateGenerator;
    private final RewardService rewardService;
    private final PKSigningInformation signingInfo;

    // Fertig signierte .pkpass-Bytes zwischenspeichern. iOS holt denselben Pass
    // nach einem Push oft mehrfach kurz hintereinander - jedes Mal neu zu bauen
    // (Stempel-PNG zeichnen + signieren + zippen) kostet auf der kleinen Instanz
    // 1-3 s. Schluessel = customerCard-ID, Eintrag gilt nur solange updatedAt
    // gleich bleibt UND hoechstens 5 Min (Sicherheitsnetz fuer Design-Aenderungen
    // am Laden, die updatedAt der Karte nicht anfassen).
    private record PassCacheEntry(long updatedAtMillis, long cachedAtMillis, byte[] bytes) {}
    // Nur ein kurzer Burst-Schutz: iOS holt denselben Pass nach einem Push oft
    // 2-3x in wenigen Sekunden. TTL bewusst klein, damit kein veralteter Pass
    // haengen bleibt (frueher 5 Min - zu lang).
    private static final long PASS_CACHE_TTL_MS = 20 * 1000L;
    private static final int PASS_CACHE_MAX = 1000;
    private final Map<String, PassCacheEntry> passCache = new ConcurrentHashMap<>();

    // Weitere Orte (ShopOrt). Null in den Tests, die den Dienst von Hand
    // bauen - dann gibt es nur den Hauptort.
    private final com.example.stemplekarte.repository.ShopOrtRepository ortRepo;

    public ApplePassService(AppProperties props, PassTemplateGenerator templateGenerator,
                            RewardService rewardService) {
        this(props, templateGenerator, rewardService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ApplePassService(AppProperties props, PassTemplateGenerator templateGenerator,
                            RewardService rewardService,
                            com.example.stemplekarte.repository.ShopOrtRepository ortRepo) {
        this.props = props;
        this.templateGenerator = templateGenerator;
        this.rewardService = rewardService;
        this.ortRepo = ortRepo;
        this.signingInfo = loadSigningInfo();
    }

    private PKSigningInformation loadSigningInfo() {
        try {
            Path b64Path = Paths.get(B64_PATH);
            Path p12Path = Paths.get(P12_PATH);
            Path wwdrPath = Paths.get(WWDR_PATH);

            if (Files.exists(b64Path)) {
                String b64 = Files.readString(b64Path).trim().replaceAll("\\s", "");
                byte[] decoded = Base64.getDecoder().decode(b64);
                Files.write(p12Path, decoded);
                log.info("Apple Pass Zertifikat decodiert ({} bytes) -> {}", decoded.length, p12Path);
            } else {
                log.warn("Apple Pass base64 Datei nicht gefunden: {}", b64Path);
                return null;
            }

            if (!Files.exists(wwdrPath)) {
                log.warn("Apple WWDR Zertifikat nicht gefunden: {}", wwdrPath);
                return null;
            }

            String password = props.apple().certPassword();
            if (password == null || password.isBlank()) {
                log.warn("Apple Cert Passwort fehlt (APPLE_CERT_PASSWORD).");
                return null;
            }

            PKSigningInformation info = new PKSigningInformationUtil()
                    .loadSigningInformationFromPKCS12AndIntermediateCertificate(
                            p12Path.toString(), password, wwdrPath.toString());
            log.info("Apple Pass Signing Information erfolgreich geladen.");
            return info;
        } catch (Exception e) {
            log.warn("Apple Pass Zertifikate nicht geladen ({}). Pass-Generierung wird fehlschlagen.",
                    e.getMessage());
            return null;
        }
    }

    public boolean isReady() {
        return signingInfo != null;
    }

    public byte[] generatePass(CustomerCard cc) throws Exception {
        if (signingInfo == null) {
            throw new IllegalStateException("Apple Wallet Zertifikate nicht konfiguriert.");
        }

        long updatedAt = cc.getUpdatedAt() != null ? cc.getUpdatedAt().toEpochMilli() : 0L;
        long now = System.currentTimeMillis();

        PassCacheEntry cached = passCache.get(cc.getId());
        if (cached != null
                && cached.updatedAtMillis() == updatedAt
                && (now - cached.cachedAtMillis()) < PASS_CACHE_TTL_MS) {
            // Bei einem Treffer wird NICHT neu gebaut. Steht hier ein Treffer
            // mit veraltetem Stempelstand, liegt der Fehler im Cache-Schluessel.
            log.info("[WALLET] PASS-BAU-CACHE serial={} stempel={} updatedAt={} alterMs={}",
                    cc.getId(), cc.getStamps(), updatedAt, now - cached.cachedAtMillis());
            return cached.bytes();
        }

        log.info("[WALLET] PASS-BAU-START serial={} stempel={} updatedAt={}",
                cc.getId(), cc.getStamps(), updatedAt);
        long t0 = System.nanoTime();
        byte[] bytes = buildPass(cc);
        long dauerMs = (System.nanoTime() - t0) / 1_000_000L;
        // Dauert der Bau auffaellig lange, haengt fast immer ein Bild-Abruf
        // (siehe [WALLET] BILD-* aus dem PassTemplateGenerator).
        log.info("[WALLET] PASS-BAU-FERTIG serial={} bytes={} dauerMs={}",
                cc.getId(), bytes.length, dauerMs);

        if (passCache.size() >= PASS_CACHE_MAX) passCache.clear();
        passCache.put(cc.getId(), new PassCacheEntry(updatedAt, now, bytes));
        return bytes;
    }

    private byte[] buildPass(CustomerCard cc) throws Exception {
        Card card = cc.getCard();
        Shop shop = card.getShop();

        String walletStyle = (card.getWalletStyle() != null && !card.getWalletStyle().isBlank())
                ? card.getWalletStyle() : shop.getWalletStyle();
        boolean grid = "grid".equalsIgnoreCase(walletStyle);

        // Gesperrt mit eigenen Willkommensfarben, sonst wie bisher Karte vor Laden.
        String[] farben = PassTemplateGenerator.passFarben(card, shop, cc);
        String bgColor = farben[0];
        String fgColor = farben[1];
        String labelColor = farben[2];

        // Konstanter, kurzer QR-Inhalt "SK:<Kundenkarte>" (QrPayload.fuer):
        // kleiner Code mit groben Punkten, aendert sich nie. Alte Karten mit
        // {"cid","cardId"} liest der Scanner weiter.
        String qrPayload = com.example.stemplekarte.service.QrPayload.fuer(cc);

        String templatePath = templateGenerator.generateTemplate(cc);

        // KEIN altText mehr → unter dem QR-Code wird die CUST-ID NICHT mehr angezeigt
        PKBarcode barcode = PKBarcode.builder()
                .format(PKBarcodeFormat.PKBarcodeFormatQR)
                .message(qrPayload)
                .messageEncoding(StandardCharsets.UTF_8)
                .build();

        PKGenericPass genericPass = baueFelder(card, cc, grid);

        var passBuilder = PKPass.builder()
                .formatVersion(1)
                .passTypeIdentifier(props.apple().passTypeIdentifier())
                .teamIdentifier(props.apple().teamIdentifier())
                .organizationName(shop.getName())
                .serialNumber(cc.getId())
                .description(card.getName())
                .logoText(logoTextFuer(shop))
                // KEIN groupingIdentifier: laut Apple nur fuer Bordkarten und
                // Event-Tickets zulaessig. Auf einer StoreCard koennen neuere
                // iOS den aktualisierten Pass bei der Validierung ablehnen ->
                // Karte installiert, aktualisiert aber nie.
                .foregroundColor(hexToRgb(fgColor))
                .backgroundColor(hexToRgb(bgColor))
                .labelColor(hexToRgb(labelColor))
                .webServiceURL(new URL(props.baseUrl() + "/wallet/"))
                .authenticationToken(cc.getAuthToken())
                .barcodes(List.of(barcode))
                .pass(genericPass);

        // Ortsfelder NUR anhaengen, wenn es wirklich einen Ort gibt. Jedes Feld,
        // das ohne Not im Pass steht, ist ein Risiko - siehe groupingIdentifier:
        // ein einziges unpassendes Feld liess iOS den aktualisierten Pass
        // verwerfen. Laeden ohne diese Funktion bekommen denselben Pass wie vorher.
        List<PKLocation> orte = sperrbildschirmOrte(shop, card, cc);
        if (!orte.isEmpty()) {
            passBuilder.maxDistance(radiusFuer(shop)).locations(orte);
        }

        PKPass pass = passBuilder.build();

        PKPassTemplateFolder template = new PKPassTemplateFolder(templatePath);
        return new PKFileBasedSigningUtil()
                .createSignedAndZippedPkPassArchive(pass, template, signingInfo);
    }

    // Ab welcher Entfernung zum Laden iOS die Karte auf dem Sperrbildschirm
    // anbietet. Der Laden waehlt im Profil. maxDistance kann den Radius nur
    // verkleinern: iOS nimmt den kleineren Wert aus unserem und seinem
    // eigenen, und der liegt bei einer StoreCard bei rund 100 m. Mehr als
    // 100 anzubieten waere deshalb Augenwischerei.
    /**
     * Mit eigener Namensfarbe steckt der Name im Logo-Bild
     * (PassTemplateGenerator#logoMitName). Apple faerbt logoText immer in der
     * Textfarbe der Karte - stuende er zusaetzlich da, kaeme der Name doppelt.
     */
    static String logoTextFuer(Shop shop) {
        return PassTemplateGenerator.eigeneNamensFarbe(shop) != null ? null : shop.getName();
    }

    public static final long LOCK_SCREEN_RADIUS_M = 100L;
    public static final List<Integer> ERLAUBTE_RADIEN = List.of(50, 75, 100);

    /** Radius des Ladens. Fehlt er oder ist er keiner der erlaubten, gilt der Standard. */
    public static long radiusFuer(Shop shop) {
        Integer r = shop.getLockScreenRadiusMeter();
        return (r != null && ERLAUBTE_RADIEN.contains(r)) ? r : LOCK_SCREEN_RADIUS_M;
    }

    /**
     * Orte fuer die Sperrbildschirm-Erinnerung.
     *
     * Die Karte meldet sich in Ladennaehe IMMER, nicht nur wenn sie voll ist -
     * eine halbvolle Karte am Sperrbildschirm ist genau der Anstoss, nochmal
     * reinzugehen. Das ist auch kein Dauerfeuer: iOS zeigt die Karte nur als
     * Vorschlag zum Hochwischen, ohne Ton und ohne Banner.
     *
     * Der Text passt sich dem Stand an, damit der Hinweis etwas aussagt statt
     * nur die Karte zu zeigen.
     *
     * Leere Liste = keine Ortsbindung, iOS zeigt dann nichts an. Das ist der
     * Fall, wenn der Laden die Funktion aus hat oder keine Koordinaten
     * hinterlegt sind.
     */
    /**
     * Baut die sichtbaren Felder des Passes.
     *
     * Bewusst getrennt von buildPass: das signiert am Ende und braucht
     * Zertifikate, die im Test nicht liegen. Die Felder sind reine
     * Datenstruktur und dadurch pruefbar - und genau sie sind der Teil, bei
     * dem ein Fehler teuer wird. Ein unpassendes Feld hat iOS schon einmal
     * den aktualisierten Pass verwerfen lassen (damals groupingIdentifier):
     * Karte installiert, aktualisiert aber nie, und gemerkt wird es erst,
     * wenn ein Kunde sich beschwert.
     *
     * Paketsichtbar, damit der Test drankommt, ohne die Klasse nach aussen
     * zu oeffnen.
     */
    PKGenericPass baueFelder(Card card, CustomerCard cc, boolean grid) {
        // Der Stempel-Weg darunter wird nicht angefasst. Das ist die harte
        // Regel dieses Umbaus: die Pässe, die gerade in echten Laeden liegen,
        // bleiben Feld fuer Feld wie sie sind.
        if (card.isPoints()) {
            var punkte = baueFelderPunkte(card, cc);
            nachrichtFeld(punkte, card.getShop(), cc);
            rueckmeldungFeld(punkte, card, cc);
            tagesangebotFeld(punkte, card.getShop(), cc);
            empfehlungFeld(punkte, card, cc);
            statusFeld(punkte, card, cc);
            return punkte.build();
        }

        // Mitgliedskarte: eigener Zweig, kein Stempelraster, keine Praemie.
        if (card.isMember()) {
            var mitglied = baueFelderMitglied(cc);
            nachrichtFeld(mitglied, card.getShop(), cc);
            rueckmeldungFeld(mitglied, card, cc);
            tagesangebotFeld(mitglied, card.getShop(), cc);
            statusFeld(mitglied, card, cc);
            return mitglied.build();
        }

        // 10er-Karte: eigener Zweig, zaehlt runter statt hoch.
        if (card.isPackage()) {
            var paket = baueFelderPaket(card, cc);
            nachrichtFeld(paket, card.getShop(), cc);
            rueckmeldungFeld(paket, card, cc);
            tagesangebotFeld(paket, card.getShop(), cc);
            statusFeld(paket, card, cc);
            return paket.build();
        }

        // Willkommensgeschenk offen: eigener Zweig, der normale Weg darunter
        // bleibt unberuehrt. Kommt nur bei Karten vor, die nach dem Einschalten
        // des Angebots neu angemeldet wurden.
        if (cc.isWillkommenOffen()) {
            var gesperrt = baueFelderGesperrt(card, cc, grid);
            nachrichtFeld(gesperrt, card.getShop(), cc);
            rueckmeldungFeld(gesperrt, card, cc);
            tagesangebotFeld(gesperrt, card.getShop(), cc);
            empfehlungFeld(gesperrt, card, cc);
            statusFeld(gesperrt, card, cc);
            return gesperrt.build();
        }

        String lang = Texts.sprache(cc.getCustomer(), card.getShop());
        int threshold = card.getRewardThreshold();
        String reward = rewardText(lang, cc.getStamps(), threshold, card.getRewardText());

        // Fortschritts-Verhältnis als String bauen (z.B. "3/10"). Gedeckelt,
        // weil ein nachtraeglich gesenkter Schwellwert sonst "10/5" anzeigt.
        String stampRatio = Math.min(cc.getStamps(), threshold) + "/" + threshold;

        // Countdown fuer das grosse Mittelfeld: wie viele Stempel noch bis zur Belohnung.
        // Zaehlt 10 -> 9 -> ... -> 1 runter, danach "Bereit!".
        int remaining = Math.max(0, threshold - cc.getStamps());
        String countdownLabel = remaining > 0 ? Texts.t(lang, "pass.countdown_label") : Texts.t(lang, "pass.reward_label");
        String countdownValue = remaining > 0 ? String.valueOf(remaining) : Texts.t(lang, "pass.ready");

        // Push-Nachricht fürs Handy beim Stempelstand-Update.
        // %@ ersetzt Apple durch den neuen Stempelstand (stampRatio).
        // istFrischFreigeschaltet ist bei jeder Karte ohne Willkommensgeschenk
        // false - fuer sie bleibt der Text wie er war.
        String changeMsg = (cc.getStamps() >= threshold)
                ? Texts.t(lang, "pass.msg_reward", card.getRewardText())
                : cc.istFrischFreigeschaltet()
                        ? Texts.t(lang, "pass.msg_unlocked")
                        : Texts.t(lang, "pass.msg_update");

        var genericPass = PKGenericPass.builder()
                .passType(PKPassType.PKStoreCard);

        if (grid) {
            genericPass
                    .headerFieldBuilder(PKField.builder()
                            .key("stamps").label(Texts.t(lang, "pass.stamps_label"))
                            .value(stampRatio)
                            .changeMessage(changeMsg))
                    .secondaryFieldBuilder(PKField.builder()
                            .key("reward").label(Texts.t(lang, "pass.reward_label")).value(reward))
                    .auxiliaryFieldBuilder(PKField.builder()
                            .key("name").label(Texts.t(lang, "pass.customer_label")).value(cc.getCustomer().getName()));
        } else {
            genericPass
                    .headerFieldBuilder(PKField.builder()
                            .key("stamps-header").label(Texts.t(lang, "pass.stamps_label"))
                            .value(stampRatio)
                            .changeMessage(changeMsg))
                    .primaryFieldBuilder(PKField.builder()
                            .key("stamps-big").label(countdownLabel).value(countdownValue))
                    .secondaryFieldBuilder(PKField.builder()
                            .key("reward").label(Texts.t(lang, "pass.reward_label")).value(reward))
                    .auxiliaryFieldBuilder(PKField.builder()
                            .key("name").label(Texts.t(lang, "pass.customer_label")).value(cc.getCustomer().getName()));
        }

        nachrichtFeld(genericPass, card.getShop(), cc);
        rueckmeldungFeld(genericPass, card, cc);
        tagesangebotFeld(genericPass, card.getShop(), cc);
        empfehlungFeld(genericPass, card, cc);
        statusFeld(genericPass, card, cc);
        return genericPass.build();
    }

    /**
     * Felder der Mitgliedskarte: Besuche im Kopf (mit changeMessage, damit
     * der Scan eine Mitteilung ausloest), Name gross, Mitglied seit und
     * eine kurze Nummer. Kein Raster, kein Ziel.
     */
    /**
     * Felder der 10er-Karte: Rest im Kopf (mit changeMessage - Abbuchen und
     * Aufladen loesen die Mitteilung aus; der Text haengt am Zustand des
     * neuen Passes), Rest gross in der Mitte, Paketgroesse, Kundenname.
     * Kein Raster, kein Streifen. Der Kopf-Key bleibt bei jedem Update
     * gleich, sonst meldet iOS nichts.
     */
    private PKGenericPassBuilder baueFelderPaket(Card card, CustomerCard cc) {
        String lang = Texts.sprache(cc.getCustomer(), card.getShop());
        int rest = cc.getPaketRest();
        String einheit = card.getPaketEinheit();
        String changeMsg = rest <= 0
                ? Texts.t(lang, "pass.msg_package_empty")
                : cc.paketFrischAufgeladen()
                        ? Texts.t(lang, "pass.msg_package_loaded", einheit)
                        : Texts.t(lang, "pass.msg_package_debit", einheit);
        return PKGenericPass.builder()
                .passType(PKPassType.PKStoreCard)
                .headerFieldBuilder(PKField.builder()
                        .key("rest-header").label(Texts.t(lang, "pass.left_label"))
                        .value(String.valueOf(rest))
                        .changeMessage(changeMsg))
                .primaryFieldBuilder(PKField.builder()
                        .key("rest-big").label(Texts.t(lang, rest > 0 ? "pass.left_big_label" : "pass.package_label"))
                        .value(rest > 0 ? String.valueOf(rest) : Texts.t(lang, "pass.package_empty")))
                .secondaryFieldBuilder(PKField.builder()
                        .key("paket").label(Texts.t(lang, "pass.package_label")).value(card.paketText()))
                .auxiliaryFieldBuilder(PKField.builder()
                        .key("name").label(Texts.t(lang, "pass.customer_label")).value(cc.getCustomer().getName()));
    }

    private PKGenericPassBuilder baueFelderMitglied(CustomerCard cc) {
        String seit = cc.getCreatedAt() != null
                ? java.time.format.DateTimeFormatter.ofPattern("MM/yyyy")
                        .format(cc.getCreatedAt().atZone(java.time.ZoneId.of("Europe/Berlin")))
                : "";
        String nummer = cc.getId() != null && cc.getId().startsWith("CC-") ? cc.getId().substring(3) : cc.getId();
        String lang = Texts.sprache(cc.getCustomer(), cc.getCard() != null ? cc.getCard().getShop() : null);
        return PKGenericPass.builder()
                .passType(PKPassType.PKStoreCard)
                .headerFieldBuilder(PKField.builder()
                        .key("visits-header").label(Texts.t(lang, "pass.visits_label"))
                        .value(String.valueOf(cc.getStamps()))
                        .changeMessage(Texts.t(lang, "pass.msg_visit")))
                .primaryFieldBuilder(PKField.builder()
                        .key("member-name").label(Texts.t(lang, "pass.member_label")).value(cc.getCustomer().getName()))
                .secondaryFieldBuilder(PKField.builder()
                        .key("member-since").label(Texts.t(lang, "pass.member_since_label")).value(seit))
                .auxiliaryFieldBuilder(PKField.builder()
                        .key("member-no").label(Texts.t(lang, "pass.member_no_label")).value(nummer != null ? nummer : ""));
    }

    /**
     * Stufe (Bronze, Silber, Gold) auf der Rueckseite, nur bei Karten mit
     * Stufen. Ohne changeMessage: der Gluckwunsch zum Aufstieg kommt ueber
     * den Automaten (AutoNachrichtTyp.STUFE), nicht ueber dieses Feld.
     */
    private void statusFeld(PKGenericPassBuilder pass, Card card, CustomerCard cc) {
        com.example.stemplekarte.service.Stufen.Stufe s = com.example.stemplekarte.service.Stufen.von(card, cc);
        if (s == null) return;
        String lang = Texts.sprache(cc.getCustomer(), card.getShop());
        pass.backFieldBuilder(PKField.builder()
                .key("status").label(Texts.t(lang, "pass.status_label"))
                .value(com.example.stemplekarte.service.Stufen.text(
                        lang, s, com.example.stemplekarte.service.Stufen.besuche(card, cc))));
    }

    /**
     * Kunde wirbt Kunde auf der Rueckseite: Satz plus Link mit dem Code der
     * Karte. Nur bei eingeschalteter Empfehlung und wenn die Karte einen
     * Code traegt. Ohne changeMessage - das Feld soll nie klingeln.
     */
    private void empfehlungFeld(PKGenericPassBuilder pass, Card card, CustomerCard cc) {
        if (!com.example.stemplekarte.service.EmpfehlungService.sichtbar(card, cc)) return;
        String lang = Texts.sprache(cc.getCustomer(), card.getShop());
        pass.backFieldBuilder(PKField.builder()
                .key("empfehlen").label(Texts.t(lang, "pass.referral_label"))
                .value(com.example.stemplekarte.service.EmpfehlungService.werbeText(lang, card) + " " + Texts.t(lang, "pass.referral_link")
                        + com.example.stemplekarte.service.EmpfehlungService.linkFuer(
                                props.baseUrl(), card.getId(), cc.getEmpfehlungsCode())));
    }

    /**
     * Tagesangebot auf der Rueckseite - nur bei Laeden, die es je benutzt
     * haben, dann dauerhaft (leer = Ruhetext), damit iOS den alten Wert
     * kennt. Ohne changeMessage: die Mitteilung kommt ueber das
     * Nachrichtenfeld der Kampagne "Heute: ...", sonst klingelt es doppelt.
     */
    private void tagesangebotFeld(PKGenericPassBuilder pass, Shop shop, CustomerCard cc) {
        if (shop == null || !shop.hatTagesangebotFeld()) return;
        String lang = Texts.sprache(cc.getCustomer(), shop);
        String angebot = shop.tagesangebot(java.time.Instant.now());
        pass.backFieldBuilder(PKField.builder()
                .key("heute").label(Texts.t(lang, "pass.today_label"))
                .value(angebot != null ? angebot : Texts.t(lang, "pass.today_none")));
    }

    /**
     * Link zur privaten Rueckmeldung auf der Rueckseite - nur wenn der Laden
     * sie eingeschaltet hat. Ohne changeMessage: das Feld soll nie klingeln,
     * es erscheint still beim naechsten Kartenupdate. Apple macht aus der
     * URL im Wert von selbst einen Link.
     */
    private void rueckmeldungFeld(PKGenericPassBuilder pass, Card card, CustomerCard cc) {
        Shop shop = card.getShop();
        if (shop == null || !shop.isRueckmeldungAktiv()) return;
        if (cc.getCustomer() == null || cc.getCustomer().getId() == null) return;
        String lang = Texts.sprache(cc.getCustomer(), shop);
        pass.backFieldBuilder(PKField.builder()
                .key("feedback").label(Texts.t(lang, "pass.feedback_label"))
                .value(Texts.t(lang, "pass.feedback_text")
                        + props.baseUrl() + "/bewertung/" + cc.getCustomer().getId() + "/" + card.getId()));
    }

    /**
     * Gesperrte Karte: nur Geschenk und QR, keine Stempel.
     *
     * Der Kopf traegt denselben Key wie im freigeschalteten Pass ("stamps"
     * im Raster-Stil, sonst "stamps-header"). Das ist der Trick: iOS meldet
     * nur Aenderungen an einem Feld, das es schon kennt. Beim Freischalten
     * springt genau dieses Feld vom Titel auf "0/10", und das changeMessage
     * des neuen Passes ("Karte freigeschaltet") erscheint als Mitteilung.
     *
     * Kein Primary-Feld: es stand gross ueber dem Streifen und verdeckte das
     * Willkommensbild. Das Geschenk steht klein in der Zeile darunter.
     * Titel und Hinweis kommen live von der Karte, das Geschenk vom Kunden.
     *
     * Jeder Text ist abschaltbar. Der Kopf bleibt dann mit leerem Wert stehen:
     * ohne ihn gaebe es beim Freischalten keinen alten Wert und keine Mitteilung.
     */
    private PKGenericPassBuilder baueFelderGesperrt(Card card, CustomerCard cc, boolean grid) {
        String geschenk = cc.getWillkommenText() != null ? cc.getWillkommenText() : Card.WILLKOMMEN_STANDARD;
        String lang = Texts.sprache(cc.getCustomer(), card.getShop());
        var pass = PKGenericPass.builder()
                .passType(PKPassType.PKStoreCard)
                .headerFieldBuilder(PKField.builder()
                        .key(grid ? "stamps" : "stamps-header")
                        .value(card.zeigtWillkommenTitel() ? card.getWillkommenTitel() : ""));
        if (card.zeigtWillkommenGeschenk()) {
            pass.secondaryFieldBuilder(PKField.builder()
                    .key("welcome").label(Texts.t(lang, "pass.gift_label")).value(geschenk));
        }
        if (card.zeigtWillkommenHinweis()) {
            pass.secondaryFieldBuilder(PKField.builder()
                    .key("welcome-how").label(Texts.t(lang, "pass.gift_how_label")).value(card.getWillkommenHinweis()));
        }
        return pass.auxiliaryFieldBuilder(PKField.builder()
                .key("name").label(Texts.t(lang, "pass.customer_label")).value(cc.getCustomer().getName()));
    }

    /**
     * Rueckseitenfeld fuer die Rueckhol-Nachricht (RueckholService).
     *
     * Nur bei Laeden, die die Funktion an haben - alle anderen Paesse bleiben
     * Feld fuer Feld wie sie sind. Das Feld steht dann IMMER im Pass, nicht
     * nur waehrend einer Nachricht: iOS meldet eine Aenderung nur sicher, wenn
     * es den alten Wert kennt. Ein Feld, das erst mit der Nachricht
     * auftaucht, kann still bleiben.
     *
     * Das changeMessage haengt nur am Feld, solange die Nachricht gilt. Kommt
     * der Kunde wieder, faellt das Feld auf den Ruhetext zurueck, ohne
     * changeMessage - sonst gaebe es beim Stempeln eine zweite Meldung.
     */
    private void nachrichtFeld(PKGenericPassBuilder pass, Shop shop, CustomerCard cc) {
        // Rueckhol an ODER schon einmal eine Kampagne verschickt: dann traegt
        // jeder Pass das Feld, dauerhaft (Shop#hatNachrichtenFeld).
        if (shop == null || !shop.hatNachrichtenFeld()) return;
        String lang = Texts.sprache(cc.getCustomer(), shop);
        var feld = PKField.builder().key("nachricht").label(Texts.t(lang, "pass.message_label"));
        String text = cc.nachrichtText();
        if (text != null) {
            feld.value(text);
            // Nur solange der Kunde seit der Nachricht nicht da war. Danach
            // steht der Text zwar noch, aber ohne changeMessage - sonst gaebe
            // es beim naechsten Stempel eine zweite Meldung.
            if (cc.nachrichtFrisch()) feld.changeMessage("%@");
        } else {
            feld.value(Texts.t(lang, "pass.message_none", shop.getName()));
        }
        pass.backFieldBuilder(feld);
    }

    /**
     * Felder einer Punktekarte.
     *
     * Vorne Stand und Ziel, hinten der ganze Katalog: der Kunde soll auf
     * einen Blick sehen, was zu holen ist, und trotzdem ein konkretes
     * naechstes Ziel haben. Ohne das Ziel verschwindet der Zugreiz, der die
     * Stempelkarte traegt ("noch zwei, dann ist der Kuchen drin").
     *
     * Das changeMessage uebernimmt die Rolle von "Karte voll": es meldet
     * sich, wenn ueberhaupt etwas einloesbar ist - nicht bei jeder Buchung,
     * sonst wird die Sperrbildschirm-Meldung zum Rauschen.
     */
    private PKGenericPassBuilder baueFelderPunkte(Card card, CustomerCard cc) {
        long stand = cc.getPointsX100();
        List<Reward> katalog = rewardService.list(card);
        Reward ziel = RewardService.naechstesZiel(katalog, stand);

        String lang = Texts.sprache(cc.getCustomer(), card.getShop());
        String standText = PointsMath.formatiere(stand);
        boolean etwasErreichbar = katalog.stream()
                .anyMatch(r -> r.getCostPointsX100() <= stand);

        // %@ ersetzt Apple durch den neuen Wert des Feldes.
        String changeMsg = etwasErreichbar
                ? Texts.t(lang, "pass.msg_points_ready")
                : Texts.t(lang, "pass.msg_points_update");

        var genericPass = PKGenericPass.builder()
                .passType(PKPassType.PKStoreCard)
                .headerFieldBuilder(PKField.builder()
                        .key("points-header").label(Texts.t(lang, "pass.points_label"))
                        .value(standText)
                        .changeMessage(changeMsg));

        if (ziel != null) {
            long fehlend = Math.max(0, ziel.getCostPointsX100() - stand);
            genericPass
                    .primaryFieldBuilder(PKField.builder()
                            .key("points-goal")
                            .label(fehlend > 0 ? Texts.t(lang, "pass.points_until_label") : Texts.t(lang, "pass.ready_label"))
                            .value(fehlend > 0
                                    ? PointsMath.formatiere(fehlend)
                                    : Texts.t(lang, "pass.ready")))
                    .secondaryFieldBuilder(PKField.builder()
                            .key("reward").label(Texts.t(lang, "pass.next_reward_label"))
                            .value(ziel.getName()));
        } else {
            // Leerer Katalog: nur der Stand, kein erfundenes Ziel. Ein Laden
            // ohne Praemien hat schlicht noch keines.
            genericPass.primaryFieldBuilder(PKField.builder()
                    .key("points-big").label(Texts.t(lang, "pass.points_label")).value(standText));
        }

        genericPass.auxiliaryFieldBuilder(PKField.builder()
                .key("name").label(Texts.t(lang, "pass.customer_label")).value(cc.getCustomer().getName()));

        // ── Rueckseite: der ganze Katalog ─────────────────────────────────
        // Das sind die ERSTEN Rueckseitenfelder ueberhaupt in diesem Pass.
        // Sie liegen bewusst nur in diesem Zweig.
        for (Reward r : katalog) {
            boolean bezahlbar = r.getCostPointsX100() <= stand;
            genericPass.backFieldBuilder(PKField.builder()
                    .key("reward-" + r.getId())
                    .label(bezahlbar ? "\u2713 " + r.getName() : r.getName())
                    .value(PointsMath.formatiere(r.getCostPointsX100()) + " " + Texts.t(lang, "unit.points")));
        }

        genericPass.backFieldBuilder(PKField.builder()
                .key("rate").label(Texts.t(lang, "pass.rate_label"))
                .value(kursText(lang, card)));

        return genericPass;
    }

    /** Der Kurs im Klartext, damit der Kunde seine Punkte selbst nachrechnen
     *  kann. Unter einem Punkt pro Euro liest sich der Kehrwert besser. */
    private String kursText(String lang, Card card) {
        int kurs = card.getPointsPerEuroX100();
        if (kurs >= 100) {
            return Texts.t(lang, "pass.rate_per_euro", PointsMath.formatiere(kurs));
        }
        long euroProPunktX100 = Math.round(10000.0 / kurs);
        return Texts.t(lang, "pass.rate_inverse", PointsMath.formatiere(euroProPunktX100));
    }

    List<PKLocation> sperrbildschirmOrte(Shop shop, Card card, CustomerCard cc) {
        if (!shop.isLockScreenActive()) {
            return List.of();
        }
        String lang = Texts.sprache(cc.getCustomer(), shop);

        // Gesperrt: in Ladennaehe an das Geschenk erinnern - genau der Moment,
        // in dem der Kunde reingehen koennte. Die Stempel-Texte des Ladens
        // passen hier nicht ("Noch 10 Stempel bis ...").
        if (cc.isWillkommenOffen()) {
            String geschenk = cc.getWillkommenText() != null
                    ? cc.getWillkommenText() : Card.WILLKOMMEN_STANDARD;
            return orte(shop, Texts.t(lang, "geo.gift", geschenk));
        }

        KartenFortschritt f = KartenFortschritt.von(card, cc, rewardService);
        // Ohne Praemie gibt es nichts Sinnvolles zu melden. Lieber keine
        // Ortsbindung als "Noch 0 Punkte bis: null".
        if (f == null) return List.of();

        String eigener = f.erreicht()
                ? shop.getLockScreenTextFull()
                : shop.getLockScreenTextProgress();

        String hinweis;
        if (eigener != null && !eigener.isBlank()) {
            hinweis = f.ersetze(eigener);
        } else {
            hinweis = f.erreicht()
                    ? Texts.t(lang, "geo.ready", f.belohnung())
                    : Texts.t(lang, "geo.progress", f.offen(), f.einheit(lang), f.belohnung());
        }

        return orte(shop, hinweis);
    }

    /** Apple nimmt hoechstens zehn Orte pro Pass. */
    static final int MAX_ORTE = 10;

    /**
     * Hauptort plus weitere Orte (ShopOrt), alle mit demselben Text. Mehr als
     * zehn verwirft iOS - dann lieber die ersten zehn als gar keinen.
     */
    private List<PKLocation> orte(Shop shop, String text) {
        List<PKLocation> orte = new java.util.ArrayList<>();
        orte.add(PKLocation.builder()
                .latitude(shop.getLatitude())
                .longitude(shop.getLongitude())
                .relevantText(text)
                .build());
        if (ortRepo != null) {
            // Nur so viele, wie der Tarif erlaubt: senkt der Admin die Zahl,
            // verschwinden die hinteren Orte aus dem Pass, ohne dass jemand
            // sie loeschen muss.
            int erlaubt = Math.min(MAX_ORTE - 1, com.example.stemplekarte.service.OrteService.erlaubt(shop));
            for (com.example.stemplekarte.model.ShopOrt o : ortRepo.findByShopOrderByCreatedAtAsc(shop)) {
                if (orte.size() >= MAX_ORTE || orte.size() > erlaubt) break;
                orte.add(PKLocation.builder()
                        .latitude(o.getLatitude())
                        .longitude(o.getLongitude())
                        .relevantText(text)
                        .build());
            }
        }
        return orte;
    }

    private String rewardText(String lang, int stamps, int threshold, String rewardText) {
        return stamps >= threshold
                ? Texts.t(lang, "pass.reward_available", rewardText)
                : rewardText;
    }

    private String hexToRgb(String hex) {
        if (hex == null || !hex.startsWith("#")) return hex;
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            return "rgb(%d,%d,%d)".formatted(r, g, b);
        } catch (Exception e) {
            return hex;
        }
    }
}