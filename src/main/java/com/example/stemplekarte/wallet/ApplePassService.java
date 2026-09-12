package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.PKField;
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

    public ApplePassService(AppProperties props, PassTemplateGenerator templateGenerator) {
        this.props = props;
        this.templateGenerator = templateGenerator;
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
        int threshold = card.getRewardThreshold();

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
        String reward = rewardText(cc.getStamps(), threshold, card.getRewardText());

        // Fortschritts-Verhältnis als String bauen (z.B. "3/10")
        String stampRatio = cc.getStamps() + "/" + threshold;

        // Countdown fuer das grosse Mittelfeld: wie viele Stempel noch bis zur Belohnung.
        // Zaehlt 10 -> 9 -> ... -> 1 runter, danach "Bereit!".
        int remaining = Math.max(0, threshold - cc.getStamps());
        String countdownLabel = remaining > 0 ? "Stempel bis ↓" : "BELOHNUNG";
        String countdownValue = remaining > 0 ? String.valueOf(remaining) : "Bereit! 🎉";

        // Push-Nachricht fürs Handy beim Stempelstand-Update.
        // %@ ersetzt Apple durch den neuen Stempelstand (stampRatio).
        // Häufigster Fall: Kunde macht die Karte genau voll (Stand == Schwelle)
        // → schöne kombinierte Nachricht mit Belohnung + Stand.
        // Sonst (normaler Stempel ODER seltener Überzieh-Fall 8+4): schlichte,
        // zuverlässige Nachricht mit dem neuen Stand. Die Belohnungs-Info
        // kommt in jedem Fall zusätzlich beim Mitarbeiter im Scanner an.
        String changeMsg = (cc.getStamps() == threshold)
                ? "🎉 " + card.getRewardText() + " verdient! Neue Karte: %@"
                : "Update! Dein Stempelstand: %@";

        // KEIN altText mehr → unter dem QR-Code wird die CUST-ID NICHT mehr angezeigt
        PKBarcode barcode = PKBarcode.builder()
                .format(PKBarcodeFormat.PKBarcodeFormatQR)
                .message(qrPayload)
                .messageEncoding(StandardCharsets.UTF_8)
                .build();

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

        PKPass pass = PKPass.builder()
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
                .pass(genericPass.build())
                .build();

        PKPassTemplateFolder template = new PKPassTemplateFolder(templatePath);
        return new PKFileBasedSigningUtil()
                .createSignedAndZippedPkPassArchive(pass, template, signingInfo);
    }

    private String rewardText(int stamps, int threshold, String rewardText) {
        return stamps >= threshold
                ? rewardText + " verfuegbar!"
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