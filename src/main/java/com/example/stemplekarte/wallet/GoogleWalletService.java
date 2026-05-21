package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.CustomerCard;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PrivateKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class GoogleWalletService {

    private static final Logger log = LoggerFactory.getLogger(GoogleWalletService.class);
    private static final String SAVE_URL_BASE = "https://pay.google.com/gp/v/save/";
    private static final String API_BASE = "https://walletobjects.googleapis.com/walletobjects/v1";
    private static final String SCOPE = "https://www.googleapis.com/auth/wallet_object.issuer";

    private final AppProperties props;
    private final ServiceAccountCredentials credentials;

    public GoogleWalletService(AppProperties props) {
        this.props = props;
        this.credentials = loadCredentials();
    }

    private ServiceAccountCredentials loadCredentials() {
        try (var fis = new FileInputStream(props.google().serviceAccountPath())) {
            ServiceAccountCredentials creds = (ServiceAccountCredentials)
                    GoogleCredentials.fromStream(fis).createScoped(List.of(SCOPE));
            return (ServiceAccountCredentials) creds;
        } catch (Exception e) {
            log.warn("Google Service Account nicht geladen: {}", e.getMessage());
            return null;
        }
    }

    public String generateSaveUrl(CustomerCard cc) {
        if (credentials == null) {
            throw new IllegalStateException("Google Service Account nicht konfiguriert.");
        }

        String issuerId = props.google().issuerId();
        String classId = issuerId + "." + props.google().classSuffix();
        String objectId = issuerId + "." + cc.getId();

        try {
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();

            String qrValue = "{\\\"cid\\\":\\\"" + cc.getCustomer().getId() +
                    "\\\",\\\"cardId\\\":\\\"" + cc.getCard().getId() +
                    "\\\",\\\"ts\\\":" + System.currentTimeMillis() + "}";

            String objectJson = String.format("""
                    {
                      "id": "%s",
                      "classId": "%s",
                      "state": "ACTIVE",
                      "accountName": "%s",
                      "accountId": "%s",
                      "loyaltyPoints": {
                        "label": "Stempel",
                        "balance": { "string": "%d/%d" }
                      },
                      "barcode": {
                        "type": "QR_CODE",
                        "value": "%s",
                        "alternateText": "%s"
                      }
                    }
                    """,
                    objectId, classId,
                    cc.getCustomer().getName(), cc.getId(),
                    cc.getStamps(), cc.getCard().getRewardThreshold(),
                    qrValue, cc.getCustomer().getId()
            );

            HttpClient http = HttpClient.newHttpClient();

            HttpRequest getReq = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/loyaltyObject/" + objectId))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> getResp = http.send(getReq, HttpResponse.BodyHandlers.ofString());
            log.info("Google Wallet GET Status: {}", getResp.statusCode());

            if (getResp.statusCode() == 404) {
                HttpRequest postReq = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + "/loyaltyObject"))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectJson))
                        .build();
                HttpResponse<String> postResp = http.send(postReq, HttpResponse.BodyHandlers.ofString());
                log.info("Google Wallet Objekt erstellt: {} (Status: {})", objectId, postResp.statusCode());
            } else {
                HttpRequest patchReq = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + "/loyaltyObject/" + objectId))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(objectJson))
                        .build();
                HttpResponse<String> patchResp = http.send(patchReq, HttpResponse.BodyHandlers.ofString());
                log.info("Google Wallet Objekt aktualisiert: {} (Status: {})", objectId, patchResp.statusCode());
            }

        } catch (Exception e) {
            log.error("Google Wallet Objekt Fehler: {}", e.getMessage());
        }

        PrivateKey privateKey = credentials.getPrivateKey();

        Map<String, Object> payload = Map.of(
                "loyaltyObjects", List.of(Map.of("id", objectId, "classId", classId))
        );

        String jwt = Jwts.builder()
                .issuer(credentials.getClientEmail())
                .audience().add("google").and()
                .claim("typ", "savetowallet")
                .claim("payload", payload)
                .issuedAt(new Date())
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        return SAVE_URL_BASE + jwt;
    }
}