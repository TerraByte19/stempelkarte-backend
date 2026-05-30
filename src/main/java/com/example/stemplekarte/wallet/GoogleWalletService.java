package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.CustomerCard;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.walletobjects.Walletobjects;
import com.google.api.services.walletobjects.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.security.PrivateKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class GoogleWalletService {

    private static final Logger log = LoggerFactory.getLogger(GoogleWalletService.class);
    private static final String SAVE_URL_BASE = "https://pay.google.com/gp/v/save/";
    private static final String SCOPE = "https://www.googleapis.com/auth/wallet_object.issuer";

    private final AppProperties props;
    private final ServiceAccountCredentials credentials;
    private final Walletobjects walletClient;

    public GoogleWalletService(AppProperties props) {
        this.props = props;
        this.credentials = loadCredentials();
        this.walletClient = buildWalletClient();
    }

    private ServiceAccountCredentials loadCredentials() {
        try (var fis = new FileInputStream(props.google().serviceAccountPath())) {
            return (ServiceAccountCredentials) GoogleCredentials
                    .fromStream(fis)
                    .createScoped(List.of(SCOPE));
        } catch (Exception e) {
            log.warn("Google Service Account nicht geladen: {}", e.getMessage());
            return null;
        }
    }

    private Walletobjects buildWalletClient() {
        if (credentials == null) return null;
        try {
            return new Walletobjects.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("Stempelkarte")
                    .build();
        } catch (Exception e) {
            log.warn("Google Wallet Client konnte nicht erstellt werden: {}", e.getMessage());
            return null;
        }
    }

    public String generateSaveUrl(CustomerCard cc) {
        if (credentials == null || walletClient == null) {
            throw new IllegalStateException("Google Service Account nicht konfiguriert.");
        }

        String issuerId = props.google().issuerId();
        String classId = issuerId + "." + props.google().classSuffix();
        String objectId = issuerId + "." + cc.getId().replace("-", "_");

        // Objekt erstellen oder aktualisieren
        try {
            createOrUpdateObject(cc, classId, objectId);
        } catch (Exception e) {
            log.error("Google Wallet Objekt Fehler: {}", e.getMessage());
        }

        // JWT generieren
        PrivateKey privateKey = credentials.getPrivateKey();

        Map<String, Object> loyaltyObject = Map.of(
                "id", objectId,
                "classId", classId
        );

        Map<String, Object> payload = Map.of(
                "loyaltyObjects", List.of(loyaltyObject)
        );

        String jwt = Jwts.builder()
                .issuer(credentials.getClientEmail())
                .audience().add("google").and()
                .claim("typ", "savetowallet")
                .claim("origins", List.of(
                        "https://stempelkarte-backend.onrender.com",
                        "https://stempelkarte-frontend.onrender.com"
                ))
                .claim("payload", payload)
                .issuedAt(new Date())
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        return SAVE_URL_BASE + jwt;
    }

    private void createOrUpdateObject(CustomerCard cc, String classId, String objectId) throws Exception {
        String qrValue = "{\"cid\":\"" + cc.getCustomer().getId() +
                "\",\"cardId\":\"" + cc.getCard().getId() +
                "\",\"ts\":" + System.currentTimeMillis() + "}";

        LoyaltyObject loyaltyObject = new LoyaltyObject()
                .setId(objectId)
                .setClassId(classId)
                .setState("ACTIVE")
                .setAccountName(cc.getCustomer().getName())
                .setAccountId(cc.getId())
                .setLoyaltyPoints(new LoyaltyPoints()
                        .setLabel("Stempel")
                        .setBalance(new LoyaltyPointsBalance()
                                .setString(cc.getStamps() + "/" + cc.getCard().getRewardThreshold())))
                .setBarcode(new Barcode()
                        .setType("QR_CODE")
                        .setValue(qrValue)
                        .setAlternateText(cc.getCustomer().getId()));

        try {
            // Prüfen ob Objekt existiert
            walletClient.loyaltyobject().get(objectId).execute();
            // Existiert → aktualisieren
            walletClient.loyaltyobject().patch(objectId, loyaltyObject).execute();
            log.info("Google Wallet Objekt aktualisiert: {}", objectId);
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                // Existiert nicht → erstellen
                walletClient.loyaltyobject().insert(loyaltyObject).execute();
                log.info("Google Wallet Objekt erstellt: {}", objectId);
            } else {
                throw e;
            }
        }
    }
}