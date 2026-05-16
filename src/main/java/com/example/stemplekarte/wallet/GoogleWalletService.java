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



}