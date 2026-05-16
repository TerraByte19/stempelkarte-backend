package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

@Service
public class GoogleWalletSetup {

    private final AppProperties props;

    public GoogleWalletSetup(AppProperties props) {
        this.props = props;
    }

    public String createLoyaltyClass() throws Exception {
        GoogleCredentials credentials = GoogleCredentials
                .fromStream(new FileInputStream(props.google().serviceAccountPath()))
                .createScoped(List.of("https://www.googleapis.com/auth/wallet_object.issuer"));

        credentials.refreshIfExpired();
        String token = credentials.getAccessToken().getTokenValue();

        String issuerId = props.google().issuerId();
        String classId = issuerId + "." + props.google().classSuffix();

        String classJson = """
        {
          "id": "%s",
          "issuerName": "Stempelkarte",
          "programName": "Treuekarte",
          "programLogo": {
            "sourceUri": {
              "uri": "https://i.imgur.com/t61DeNF.png"
            },
            "contentDescription": {
              "defaultValue": {
                "language": "de-DE",
                "value": "Treuekarte Logo"
              }
            }
          },
          "hexBackgroundColor": "#3C3489",
          "reviewStatus": "UNDER_REVIEW",
          "countryCode": "DE"
        }
        """.formatted(classId);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://walletobjects.googleapis.com/walletobjects/v1/loyaltyClass"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(classJson))
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 200) {
            return "Loyalty Class erstellt: " + classId;
        } else if (resp.statusCode() == 409) {
            return "Loyalty Class existiert bereits (OK): " + classId;
        } else {
            throw new RuntimeException("Google API Fehler (" + resp.statusCode() + "): " + resp.body());
        }
    }
}