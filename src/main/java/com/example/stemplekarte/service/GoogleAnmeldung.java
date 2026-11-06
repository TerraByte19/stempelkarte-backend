package com.example.stemplekarte.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * "Mit Google anmelden" beim Kartenholen: der Kunde tippt auf den
 * Google-Knopf, Google liefert ein ID-Token, wir pruefen es und kennen Name
 * und (bestaetigte) E-Mail. Die Bestaetigungs-Mail entfaellt, die Karte
 * liegt sofort in der Wallet.
 *
 * Aktiv nur mit GOOGLE_CLIENT_ID (Google Cloud Console, OAuth-Client vom
 * Typ Webanwendung, Frontend- und Backend-URL als autorisierte JavaScript-
 * Quellen). Ohne Client-ID zeigt die Anmeldeseite keinen Google-Knopf.
 *
 * Geprueft wird ueber Googles tokeninfo-Endpunkt: der prueft Signatur und
 * Ablauf, wir pruefen Herausgeber, Zielgruppe (unsere Client-ID) und dass
 * Google die Adresse bestaetigt hat.
 */
@Service
public class GoogleAnmeldung {

    private static final Logger log = LoggerFactory.getLogger(GoogleAnmeldung.class);
    private static final Set<String> HERAUSGEBER = Set.of("accounts.google.com", "https://accounts.google.com");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Profil(String email, String name) {}

    // Hoechstens so viele Versuche je IP in 10 Minuten: jeder Versuch ist
    // eine Anfrage an Google, die bis zu 8 Sekunden dauern kann.
    static final int MAX_JE_IP = 20;
    private final Drossel drossel = new Drossel(MAX_JE_IP, Duration.ofMinutes(10));

    private final String clientId;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public GoogleAnmeldung(@Value("${stempelkarte.google.client-id:}") String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public boolean aktiv() {
        return !clientId.isBlank();
    }

    public String clientId() {
        return clientId;
    }

    /** Wirft bei zu vielen Versuchen von derselben Adresse. */
    public void drossel(String ip, java.time.Instant jetzt) {
        drossel.pruefe(ip, jetzt);
    }

    /** IllegalArgumentException (400) mit Klartext, wenn das Token nicht passt. */
    public Profil pruefe(String idToken) {
        if (!aktiv()) throw new IllegalArgumentException("Anmeldung mit Google ist nicht eingerichtet");
        if (idToken == null || idToken.isBlank() || idToken.length() > 4096) {
            throw new IllegalArgumentException("Google-Anmeldung fehlgeschlagen");
        }
        JsonNode info = tokeninfo(idToken);
        if (info == null) throw new IllegalArgumentException("Google-Anmeldung fehlgeschlagen");
        if (!clientId.equals(info.path("aud").asText())) {
            log.warn("[GOOGLE] Token fuer fremde Client-ID abgelehnt");
            throw new IllegalArgumentException("Google-Anmeldung fehlgeschlagen");
        }
        if (!HERAUSGEBER.contains(info.path("iss").asText())) {
            throw new IllegalArgumentException("Google-Anmeldung fehlgeschlagen");
        }
        if (!"true".equals(info.path("email_verified").asText())) {
            throw new IllegalArgumentException("Deine Google-Adresse ist nicht bestätigt");
        }
        String email = info.path("email").asText("").trim();
        if (email.isBlank()) throw new IllegalArgumentException("Google-Anmeldung fehlgeschlagen");
        String name = info.path("name").asText("").trim();
        if (name.isBlank()) name = info.path("given_name").asText("").trim();
        if (name.isBlank()) {
            int at = email.indexOf('@');
            name = at > 0 ? email.substring(0, at) : email;
        }
        return new Profil(email, name);
    }

    /** Antwort von Google als JSON, null bei Ablehnung. Fuer Tests ueberschreibbar. */
    protected JsonNode tokeninfo(String idToken) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://oauth2.googleapis.com/tokeninfo?id_token="
                            + URLEncoder.encode(idToken, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.info("[GOOGLE] tokeninfo {}", res.statusCode());
                return null;
            }
            return MAPPER.readTree(res.body());
        } catch (Exception e) {
            log.warn("[GOOGLE] tokeninfo fehlgeschlagen: {}", e.getMessage());
            return null;
        }
    }
}
