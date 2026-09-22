package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.PointsMath;
import com.example.stemplekarte.service.RewardService;
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.PKLocation;
import de.brendamour.jpasskit.PKPass;
import de.brendamour.jpasskit.enums.PKBarcodeFormat;
import de.brendamour.jpasskit.enums.PKPassType;
import de.brendamour.jpasskit.passes.PKGenericPass;
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

    public ApplePassService(AppProperties props, PassTemplateGenerator templateGenerator,
                            RewardService rewardService) {
        this.props = props;
        this.templateGenerator = templateGenerator;
        this.rewardService = rewardService;
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

        String bgColor = (card.getColorBackground() != null && !card.getColorBackground().isBlank()) ? card.getColorBackground() : shop.getColorBackground();
        String fgColor = (card.getColorForeground() != null && !card.getColorForeground().isBlank()) ? card.getColorForeground() : shop.getColorForeground();
        String labelColor = (card.getColorLabel() != null && !card.getColorLabel().isBlank()) ? card.getColorLabel() : shop.getColorLabel();

        // Konstanter QR-Inhalt: nur cid + cardId. Kein Zeitstempel mehr, damit
        // sich das QR-Bild nicht bei jedem Scan aendert (der Scanner liest nur
        // cid/cardId). Stabileres Bild = besseres Caching bei Apple.
        String qrPayload = "{\"cid\":\"%s\",\"cardId\":\"%s\"}"
                .formatted(cc.getCustomer().getId(), card.getId());

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
                .logoText(shop.getName())
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
            passBuilder.maxDistance(LOCK_SCREEN_RADIUS_M).locations(orte);
        }

        PKPass pass = passBuilder.build();

        PKPassTemplateFolder template = new PKPassTemplateFolder(templatePath);
        return new PKFileBasedSigningUtil()
                .createSignedAndZippedPkPassArchive(pass, template, signingInfo);
    }

    // Ab welcher Entfernung zum Laden iOS die Karte auf dem Sperrbildschirm
    // anbietet. 150 m ist nah genug, dass der Kunde wirklich vor Ort ist, und
    // weit genug, dass es schon beim Ankommen erscheint.
    private static final long LOCK_SCREEN_RADIUS_M = 150L;

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
            return baueFelderPunkte(card, cc);
        }

        int threshold = card.getRewardThreshold();
        String reward = rewardText(cc.getStamps(), threshold, card.getRewardText());

        // Fortschritts-Verhältnis als String bauen (z.B. "3/10"). Gedeckelt,
        // weil ein nachtraeglich gesenkter Schwellwert sonst "10/5" anzeigt.
        String stampRatio = Math.min(cc.getStamps(), threshold) + "/" + threshold;

        // Countdown fuer das grosse Mittelfeld: wie viele Stempel noch bis zur Belohnung.
        // Zaehlt 10 -> 9 -> ... -> 1 runter, danach "Bereit!".
        int remaining = Math.max(0, threshold - cc.getStamps());
        String countdownLabel = remaining > 0 ? "Stempel bis \u2193" : "BELOHNUNG";
        String countdownValue = remaining > 0 ? String.valueOf(remaining) : "Bereit! \uD83C\uDF89";

        // Push-Nachricht fürs Handy beim Stempelstand-Update.
        // %@ ersetzt Apple durch den neuen Stempelstand (stampRatio).
        String changeMsg = (cc.getStamps() >= threshold)
                ? "\uD83C\uDF89 " + card.getRewardText() + " verdient! Neue Karte: %@"
                : "Update! Dein Stempelstand: %@";

        var genericPass = PKGenericPass.builder()
                .passType(PKPassType.PKStoreCard);

        if (grid) {
            genericPass
                    .headerFieldBuilder(PKField.builder()
                            .key("stamps").label("STEMPEL")
                            .value(stampRatio)
                            .changeMessage(changeMsg))
                    .secondaryFieldBuilder(PKField.builder()
                            .key("reward").label("BELOHNUNG").value(reward))
                    .auxiliaryFieldBuilder(PKField.builder()
                            .key("name").label("KUNDE").value(cc.getCustomer().getName()));
        } else {
            genericPass
                    .headerFieldBuilder(PKField.builder()
                            .key("stamps-header").label("STEMPEL")
                            .value(stampRatio)
                            .changeMessage(changeMsg))
                    .primaryFieldBuilder(PKField.builder()
                            .key("stamps-big").label(countdownLabel).value(countdownValue))
                    .secondaryFieldBuilder(PKField.builder()
                            .key("reward").label("BELOHNUNG").value(reward))
                    .auxiliaryFieldBuilder(PKField.builder()
                            .key("name").label("KUNDE").value(cc.getCustomer().getName()));
        }

        return genericPass.build();
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
    private PKGenericPass baueFelderPunkte(Card card, CustomerCard cc) {
        long stand = cc.getPointsX100();
        List<Reward> katalog = rewardService.list(card);
        Reward ziel = RewardService.naechstesZiel(katalog, stand);

        String standText = PointsMath.formatiere(stand);
        boolean etwasErreichbar = katalog.stream()
                .anyMatch(r -> r.getCostPointsX100() <= stand);

        // %@ ersetzt Apple durch den neuen Wert des Feldes.
        String changeMsg = etwasErreichbar
                ? "\uD83C\uDF89 Du kannst einloesen! Punktestand: %@"
                : "Update! Dein Punktestand: %@";

        var genericPass = PKGenericPass.builder()
                .passType(PKPassType.PKStoreCard)
                .headerFieldBuilder(PKField.builder()
                        .key("points-header").label("PUNKTE")
                        .value(standText)
                        .changeMessage(changeMsg));

        if (ziel != null) {
            long fehlend = Math.max(0, ziel.getCostPointsX100() - stand);
            genericPass
                    .primaryFieldBuilder(PKField.builder()
                            .key("points-goal")
                            .label(fehlend > 0 ? "Punkte bis \u2193" : "BEREIT")
                            .value(fehlend > 0
                                    ? PointsMath.formatiere(fehlend)
                                    : "Bereit! \uD83C\uDF89"))
                    .secondaryFieldBuilder(PKField.builder()
                            .key("reward").label("N\u00c4CHSTE PR\u00c4MIE")
                            .value(ziel.getName()));
        } else {
            // Leerer Katalog: nur der Stand, kein erfundenes Ziel. Ein Laden
            // ohne Praemien hat schlicht noch keines.
            genericPass.primaryFieldBuilder(PKField.builder()
                    .key("points-big").label("PUNKTE").value(standText));
        }

        genericPass.auxiliaryFieldBuilder(PKField.builder()
                .key("name").label("KUNDE").value(cc.getCustomer().getName()));

        // ── Rueckseite: der ganze Katalog ─────────────────────────────────
        // Das sind die ERSTEN Rueckseitenfelder ueberhaupt in diesem Pass.
        // Sie liegen bewusst nur in diesem Zweig.
        for (Reward r : katalog) {
            boolean bezahlbar = r.getCostPointsX100() <= stand;
            genericPass.backFieldBuilder(PKField.builder()
                    .key("reward-" + r.getId())
                    .label(bezahlbar ? "\u2713 " + r.getName() : r.getName())
                    .value(PointsMath.formatiere(r.getCostPointsX100()) + " Punkte"));
        }

        genericPass.backFieldBuilder(PKField.builder()
                .key("rate").label("So sammelst du")
                .value(kursText(card)));

        return genericPass.build();
    }

    /** Der Kurs im Klartext, damit der Kunde seine Punkte selbst nachrechnen
     *  kann. Unter einem Punkt pro Euro liest sich der Kehrwert besser. */
    private String kursText(Card card) {
        int kurs = card.getPointsPerEuroX100();
        if (kurs >= 100) {
            return PointsMath.formatiere(kurs) + " Punkte pro Euro";
        }
        long euroProPunktX100 = Math.round(10000.0 / kurs);
        return "1 Punkt pro " + PointsMath.formatiere(euroProPunktX100) + " Euro";
    }

    List<PKLocation> sperrbildschirmOrte(Shop shop, Card card, CustomerCard cc) {
        if (!shop.isLockScreenActive()) {
            return List.of();
        }

        String belohnung;
        String offen;
        String einheit;
        boolean erreicht;

        if (card.isPoints()) {
            List<Reward> katalog = rewardService.list(card);
            Reward ziel = RewardService.naechstesZiel(katalog, cc.getPointsX100());
            // Ohne Praemie gibt es nichts Sinnvolles zu melden. Lieber keine
            // Ortsbindung als "Noch 0 Punkte bis: null".
            if (ziel == null) return List.of();
            long fehlend = Math.max(0, ziel.getCostPointsX100() - cc.getPointsX100());
            belohnung = ziel.getName();
            offen = PointsMath.formatiere(fehlend);
            einheit = "Punkte";
            erreicht = fehlend == 0;
        } else {
            int fehlend = card.getRewardThreshold() - cc.getStamps();
            belohnung = card.getRewardText();
            offen = String.valueOf(Math.max(0, fehlend));
            // Einzahl beibehalten: "Noch 1 Stempel bis:" stand so schon da.
            einheit = fehlend == 1 ? "Stempel" : "Stempel";
            erreicht = fehlend <= 0;
        }

        String eigener = erreicht
                ? shop.getLockScreenTextFull()
                : shop.getLockScreenTextProgress();

        String hinweis;
        if (eigener != null && !eigener.isBlank()) {
            // Deutsche und englische Schreibweise, damit ein Laden den
            // Platzhalter so nutzen kann, wie er in seiner Oberflaeche steht.
            // Bei Punktekarten traegt {stempel}/{stamps} die fehlenden Punkte
            // und {belohnung}/{reward} den Namen der naechsten Praemie - ein
            // Text, den ein Laden fuer seine Stempelkarte geschrieben hat,
            // laeuft damit unveraendert auch auf einer Punktekarte.
            hinweis = eigener
                    .replace("{belohnung}", belohnung)
                    .replace("{reward}", belohnung)
                    .replace("{stempel}", offen)
                    .replace("{stamps}", offen);
        } else {
            hinweis = erreicht
                    ? belohnung + " wartet auf dich"
                    : "Noch " + offen + " " + einheit + " bis: " + belohnung;
        }

        return List.of(PKLocation.builder()
                .latitude(shop.getLatitude())
                .longitude(shop.getLongitude())
                .relevantText(hinweis)
                .build());
    }

    private String rewardText(int stamps, int threshold, String rewardText) {
        return stamps >= threshold
                ? rewardText + " verfügbar!"
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