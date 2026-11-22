package com.example.stemplekarte.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Ortssuche ueber OpenStreetMap (Nominatim). Gesendet wird nur die Adresse
 * einer Filiale - nie Kundendaten. Der Dienst erlaubt hoechstens eine
 * Anfrage pro Sekunde; die Pause haelt der Aufrufer ein (FilialImportService).
 */
@Component
public class NominatimGeocoder implements Geocoder {

    private static final Logger log = LoggerFactory.getLogger(NominatimGeocoder.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String url;
    private final String userAgent;
    private final String laender;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public NominatimGeocoder(
            @Value("${stempelkarte.geocoder.url:https://nominatim.openstreetmap.org}") String url,
            @Value("${stempelkarte.geocoder.user-agent:stempelkarte-backend/1.0}") String userAgent,
            @Value("${stempelkarte.geocoder.laender:de}") String laender) {
        this.url = url;
        this.userAgent = userAgent;
        this.laender = laender;
    }

    @Override
    public Optional<Punkt> suche(String adresse) {
        if (adresse == null || adresse.isBlank()) return Optional.empty();
        try {
            String q = url + "/search?format=jsonv2&limit=1"
                    + (laender == null || laender.isBlank() ? "" : "&countrycodes=" + enc(laender))
                    + "&q=" + enc(adresse.trim());
            HttpRequest req = HttpRequest.newBuilder(URI.create(q))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("[GEOCODER] status={}", resp.statusCode());
                return Optional.empty();
            }
            return lies(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[GEOCODER] Fehler: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Antwort des Dienstes lesen. Eigene Methode, damit sie ohne Netz testbar ist. */
    static Optional<Punkt> lies(String body) {
        try {
            JsonNode liste = JSON.readTree(body);
            if (liste == null || !liste.isArray() || liste.isEmpty()) return Optional.empty();
            JsonNode t = liste.get(0);
            double lat = Double.parseDouble(t.path("lat").asText());
            double lon = Double.parseDouble(t.path("lon").asText());
            if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                return Optional.empty();
            }
            return Optional.of(new Punkt(lat, lon, t.path("display_name").asText("")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
