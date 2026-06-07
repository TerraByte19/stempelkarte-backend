package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
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

@Service
public class ApplePassService {

    private static final Logger log = LoggerFactory.getLogger(ApplePassService.class);

    private static final String B64_PATH = "/etc/secrets/pass-certificate.b64";
    private static final String P12_PATH = "/tmp/pass-certificate.p12";
    private static final String WWDR_PATH = "/etc/secrets/apple-wwdr.pem";

    private final AppProperties props;
    private final PassTemplateGenerator templateGenerator;
    private final PKSigningInformation signingInfo;

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

        int threshold = cc.getCard().getRewardThreshold();
        Shop shop = cc.getCard().getShop();
        boolean grid = "grid".equalsIgnoreCase(shop.getWalletStyle());
        int remaining = Math.max(0, threshold - cc.getStamps());
        boolean ready = remaining <= 0;
        String rewardText = cc.getCard().getRewardText();

        log.info("Pass-Generierung {} | walletStyle='{}' grid={} stamps={} threshold={} remaining={} reward='{}'",
                cc.getId(), shop.getWalletStyle(), grid, cc.getStamps(), threshold, remaining, rewardText);

        String qrPayload = "{\"cid\":\"%s\",\"cardId\":\"%s\",\"ts\":%d}"
                .formatted(cc.getCustomer().getId(), cc.getCard().getId(),
                        System.currentTimeMillis());

        String templatePath = templateGenerator.generateTemplate(cc);

        var genericPass = PKGenericPass.builder()
                .passType(PKPassType.PKStoreCard)
                .headerFieldBuilder(PKField.builder()
                        .key("stamps").label("STEMPEL")
                        .value(String.valueOf(cc.getStamps())));

        if (grid) {
            genericPass
                    .secondaryFieldBuilder(PKField.builder()
                            .key("reward").label("BELOHNUNG").value(rewardText))
                    .auxiliaryFieldBuilder(PKField.builder()
                            .key("name").label("KUNDE").value(cc.getCustomer().getName()));
        } else {
            genericPass
                    .primaryFieldBuilder(PKField.builder()
                            .key("remaining")
                            .label(ready ? "Belohnung bereit" : "Stempel bis " + rewardText)
                            .value(ready ? "Bereit!" : String.valueOf(remaining)))
                    .secondaryFieldBuilder(PKField.builder()
                            .key("card").label("KARTE").value(cc.getCard().getName()))
                    .auxiliaryFieldBuilder(PKField.builder()
                            .key("threshold").label("STEMPEL GESAMT")
                            .value(String.valueOf(threshold)));
        }

        PKPass pass = PKPass.builder()
                .formatVersion(1)
                .passTypeIdentifier(props.apple().passTypeIdentifier())
                .teamIdentifier(props.apple().teamIdentifier())
                .organizationName(shop.getName())
                .serialNumber(cc.getId())
                .description(cc.getCard().getName())
                .logoText(shop.getName())
                // NACHHER (Die korrekte Version):
                .foregroundColor(hexToRgb(cc.getCard().getColorForeground()))
                .backgroundColor(hexToRgb(cc.getCard().getColorBackground()))
                .labelColor(hexToRgb(cc.getCard().getColorLabel()))
                .webServiceURL(new URL(props.baseUrl() + "/wallet/"))
                .authenticationToken(cc.getAuthToken())
                .pass(genericPass.build())
                .barcodeBuilder(PKBarcode.builder()
                        .format(PKBarcodeFormat.PKBarcodeFormatQR)
                        .message(qrPayload)
                        .messageEncoding(StandardCharsets.UTF_8)
                        .altText(cc.getCustomer().getId()))
                .build();

        PKPassTemplateFolder template = new PKPassTemplateFolder(templatePath);
        return new PKFileBasedSigningUtil()
                .createSignedAndZippedPkPassArchive(pass, template, signingInfo);
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