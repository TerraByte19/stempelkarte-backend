package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.CustomerCard;
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

@Service
public class ApplePassService {

    private static final Logger log = LoggerFactory.getLogger(ApplePassService.class);

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
            return new PKSigningInformationUtil()
                    .loadSigningInformationFromPKCS12AndIntermediateCertificate(
                            props.apple().certPath(),
                            props.apple().certPassword(),
                            props.apple().wwdrCertPath()
                    );
        } catch (Exception e) {
            log.warn("Apple Pass Zertifikate nicht geladen ({}). Pass-Generierung wird fehlschlagen bis Zertifikate vorhanden sind.",
                    e.getMessage());
            return null;
        }
    }

    public byte[] generatePass(CustomerCard cc) throws Exception {
        if (signingInfo == null) {
            throw new IllegalStateException("Apple Wallet Zertifikate nicht konfiguriert.");
        }

        int threshold = cc.getCard().getRewardThreshold();
        var shop = cc.getCard().getShop();

        String qrPayload = "{\"cid\":\"%s\",\"cardId\":\"%s\",\"ts\":%d}"
                .formatted(cc.getCustomer().getId(), cc.getCard().getId(),
                        System.currentTimeMillis());

        // Dynamisches Template pro Laden generieren
        String templatePath = templateGenerator.generateTemplate(shop);

        PKPass pass = PKPass.builder()
                .formatVersion(1)
                .passTypeIdentifier(props.apple().passTypeIdentifier())
                .teamIdentifier(props.apple().teamIdentifier())
                .organizationName(shop.getName())
                .serialNumber(cc.getId())
                .description(cc.getCard().getName())
                .logoText(shop.getName())
                .foregroundColor(hexToRgb(shop.getColorForeground()))
                .backgroundColor(hexToRgb(shop.getColorBackground()))
                .labelColor(hexToRgb(shop.getColorLabel()))
                .webServiceURL(new URL(props.baseUrl() + "/wallet/"))
                .authenticationToken(cc.getAuthToken())
                .pass(PKGenericPass.builder()
                        .passType(PKPassType.PKStoreCard)
                        .headerFieldBuilder(PKField.builder()
                                .key("stamps")
                                .label("STEMPEL")
                                .value(cc.getStamps() + "/" + threshold))
                        .primaryFieldBuilder(PKField.builder()
                                .key("reward")
                                .label("BELOHNUNG")
                                .value(rewardText(cc.getStamps(), threshold,
                                        cc.getCard().getRewardText())))
                        .secondaryFieldBuilder(PKField.builder()
                                .key("name")
                                .label("KUNDE")
                                .value(cc.getCustomer().getName()))
                        .auxiliaryFieldBuilder(PKField.builder()
                                .key("rewards-total")
                                .label("EINGELOEST")
                                .value(String.valueOf(cc.getTotalRewards()))))
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

    private String rewardText(int stamps, int threshold, String rewardText) {
        return stamps >= threshold
                ? rewardText + " verfuegbar!"
                : "Noch " + (threshold - stamps) + " bis: " + rewardText;
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