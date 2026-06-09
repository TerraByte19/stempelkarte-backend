package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.PrivateKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class GoogleWalletService {

    private static final Logger log = LoggerFactory.getLogger(GoogleWalletService.class);
    private static final String SAVE_URL_BASE = "https://pay.google.com/gp/v/save/";
    private static final String SCOPE = "https://www.googleapis.com/auth/wallet_object.issuer";

    private final AppProperties props;
    private final CustomerCardRepository customerCardRepo;
    private final ServiceAccountCredentials credentials;
    private final Walletobjects walletClient;

    public GoogleWalletService(AppProperties props, CustomerCardRepository customerCardRepo) {
        this.props = props;
        this.customerCardRepo = customerCardRepo;
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

    private String classIdFor(Shop shop) {
        return props.google().issuerId() + ".shop_" + shop.getId().replace("-", "_");
    }

    private String objectIdFor(CustomerCard cc) {
        return props.google().issuerId() + "." + cc.getId().replace("-", "_");
    }

    public String generateSaveUrl(CustomerCard cc) {
        if (credentials == null || walletClient == null) {
            throw new IllegalStateException("Google Service Account nicht konfiguriert.");
        }

        Shop shop = cc.getCard().getShop();
        String classId = classIdFor(shop);
        String objectId = objectIdFor(cc);

        try {
            // Beim Hinzufuegen zur Wallet: Class + Object einmalig anlegen/aktualisieren.
            createOrUpdateClass(cc, classId);
            createOrUpdateObject(cc, classId, objectId);
        } catch (Exception e) {
            log.error("Google Wallet Fehler im Lifecycle: {}", e.getMessage());
        }

        PrivateKey privateKey = credentials.getPrivateKey();

        Map<String, Object> loyaltyObject = Map.of(
                "id", objectId,
                "classId", classId
        );
        Map<String, Object> payload = Map.of(
                "loyaltyObjects", List.of(loyaltyObject)
        );

        return SAVE_URL_BASE + Jwts.builder()
                .issuer(credentials.getClientEmail())
                .audience().single("google")
                .claim("typ", "savetowallet")
                .claim("origins", List.of(
                        "https://stempelkarte-backend.onrender.com",
                        "https://stempelkarte-frontend.onrender.com"
                ))
                .claim("payload", payload)
                .issuedAt(new Date())
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    @Async
    @Transactional
    public void notifyUpdate(String customerCardId) {
        if (credentials == null || walletClient == null) return;
        try {
            CustomerCard cc = customerCardRepo.findById(customerCardId)
                    .orElseThrow(() -> new NoSuchElementException("CustomerCard nicht gefunden: " + customerCardId));
            String classId = classIdFor(cc.getCard().getShop());
            createOrUpdateObject(cc, classId, objectIdFor(cc));
        } catch (Exception e) {
            log.error("Google Wallet Update Fehler: {}", e.getMessage());
        }
    }

    private void createOrUpdateClass(CustomerCard cc, String classId) throws Exception {
        Shop shop = cc.getCard().getShop();
        com.example.stemplekarte.model.Card card = cc.getCard();

        String bgColor = card.getColorBackground() != null ? card.getColorBackground() : shop.getColorBackground();
        String logoUrl = (card.getLogoUrl() != null && !card.getLogoUrl().isBlank()) ? card.getLogoUrl() : shop.getLogoUrl();
        String heroUrl = (card.getHeroImageUrl() != null && !card.getHeroImageUrl().isBlank()) ? card.getHeroImageUrl() : shop.getHeroImageUrl();

        LoyaltyClass lc = new LoyaltyClass()
                .setId(classId)
                .setIssuerName(shop.getName())
                .setProgramName(card.getName())
                .setHexBackgroundColor(safeColor(bgColor))
                .setReviewStatus("underReview")
                .setCountryCode("DE");

        if (isImageReachable(logoUrl)) {
            lc.setProgramLogo(new Image().setSourceUri(new ImageUri().setUri(logoUrl)));
        }
        if (isImageReachable(heroUrl)) {
            lc.setHeroImage(new Image().setSourceUri(new ImageUri().setUri(heroUrl)));
        }

        try {
            walletClient.loyaltyclass().get(classId).execute();
            walletClient.loyaltyclass().patch(classId, lc).execute();
            log.info("Google Wallet Class aktualisiert: {}", classId);
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                try {
                    walletClient.loyaltyclass().insert(lc).execute();
                    log.info("Google Wallet Class erstellt: {}", classId);
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException ex) {
                    if (ex.getStatusCode() == 409) {
                        log.warn("Google Wallet Class parallel erstellt (409), wechsle zu Patch: {}", classId);
                        walletClient.loyaltyclass().patch(classId, lc).execute();
                    } else {
                        throw ex;
                    }
                }
            } else if (e.getStatusCode() == 409) {
                log.warn("Google Wallet Class Concurrency Conflict (409) bei Patch: {}", classId);
                walletClient.loyaltyclass().patch(classId, lc).execute();
            } else {
                throw e;
            }
        }
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
                .setSecondaryLoyaltyPoints(new LoyaltyPoints()
                        .setLabel("Belohnung")
                        .setBalance(new LoyaltyPointsBalance()
                                .setString(cc.getCard().getRewardText())))
                .setBarcode(new Barcode()
                        .setType("QR_CODE")
                        .setValue(qrValue)
                        .setAlternateText(cc.getCustomer().getId()));

        try {
            walletClient.loyaltyobject().get(objectId).execute();
            walletClient.loyaltyobject().patch(objectId, loyaltyObject).execute();
            log.info("Google Wallet Objekt aktualisiert: {}", objectId);
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                try {
                    walletClient.loyaltyobject().insert(loyaltyObject).execute();
                    log.info("Google Wallet Objekt erstellt: {}", objectId);
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException ex) {
                    if (ex.getStatusCode() == 409) {
                        log.warn("Google Wallet Objekt parallel erstellt (409), wechsle zu Patch: {}", objectId);
                        walletClient.loyaltyobject().patch(objectId, loyaltyObject).execute();
                    } else {
                        throw ex;
                    }
                }
            } else if (e.getStatusCode() == 409) {
                log.warn("Google Wallet Objekt Concurrency Conflict (409) bei Patch: {}", objectId);
                walletClient.loyaltyobject().patch(objectId, loyaltyObject).execute();
            } else {
                throw e;
            }
        }
    }

    private boolean isHttps(String url) {
        return url != null && url.startsWith("https://");
    }

    private boolean isImageReachable(String url) {
        if (!isHttps(url)) return false;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String safeColor(String c) {
        return (c != null && c.startsWith("#")) ? c : "#3C3489";
    }
}