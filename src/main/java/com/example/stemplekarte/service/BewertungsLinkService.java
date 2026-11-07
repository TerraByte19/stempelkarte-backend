package com.example.stemplekarte.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Gratis-Tool "Google-Bewertungslink erzeugen" auf der Website: der Laden
 * tippt seinen Namen, wir suchen ihn bei Google Places und geben den Link
 * "Bewerte uns bei Google" (search.google.com/local/writereview?placeid=...)
 * plus QR-Code zurueck. Kostenlos, ohne Login - und darunter steht Stampit.
 *
 * Schutz der Google-Kosten: Ergebnisse 24 h im Speicher, Drossel je IP,
 * dazu ein fester Deckel je Tag fuer alle zusammen - die IP kommt aus
 * X-Forwarded-For und laesst sich vom Aufrufer faelschen, der Tagesdeckel
 * nicht. Hoechstens fuenf Treffer, nur die drei Felder id/Name/Adresse. Ohne
 * GOOGLE_PLACES_API_KEY ist das Tool aus; die Seite bietet dann nur die
 * Eingabe einer Place-ID von Hand an.
 */
@Service
public class BewertungsLinkService {

    private static final Logger log = LoggerFactory.getLogger(BewertungsLinkService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String LINK = ShopService.PLACE_ID_LINK;
    static final String SUCHE_URL = "https://places.googleapis.com/v1/places:searchText";
    static final Pattern PLACE_ID = Pattern.compile("^[A-Za-z0-9_-]{10,300}$");
    static final int Q_MIN = 3;
    static final int Q_MAX = 120;
    static final int MAX_TREFFER = 5;
    static final int MAX_JE_IP = 20;
    /** Google-Anfragen je Tag insgesamt. Bei ~0,03 EUR je Anfrage ist das die Obergrenze der Rechnung. */
    static final int MAX_JE_TAG = 1500;
    static final String TAG_VOLL = "Das Tool ist für heute ausgelastet. Bitte morgen noch einmal.";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    static final Duration CACHE_DAUER = Duration.ofHours(24);
    static final int CACHE_MAX = 5000;

    public record Treffer(String placeId, String name, String adresse, String link) {}
    /** aktiv = Suche moeglich (Schluessel da). Ohne Schluessel leere Liste. */
    public record Ergebnis(boolean aktiv, List<Treffer> treffer) {}

    private record Eintrag(Instant am, List<Treffer> treffer) {}

    private final String apiKey;
    private final Drossel drossel = new Drossel(MAX_JE_IP, Duration.ofMinutes(10));
    private final Map<String, Eintrag> cache = new ConcurrentHashMap<>();
    private LocalDate zaehlTag;
    private int heute;

    public BewertungsLinkService(@Value("${stempelkarte.google.places-api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean aktiv() {
        return !apiKey.isBlank();
    }

    public Ergebnis suche(String roh, String ip, Instant jetzt) {
        if (!aktiv()) return new Ergebnis(false, List.of());
        String q = sauber(roh);
        if (q.length() < Q_MIN) throw new IllegalArgumentException("Bitte mindestens drei Zeichen eingeben.");
        String key = q.toLowerCase();
        Eintrag alt = cache.get(key);
        if (alt != null && alt.am().plus(CACHE_DAUER).isAfter(jetzt)) {
            return new Ergebnis(true, alt.treffer());
        }
        // Nur echte Anfragen an Google zaehlen fuer Drossel und Tagesdeckel - Treffer aus dem Speicher sind gratis.
        drossel.pruefe(ip, jetzt);
        tagesdeckel(jetzt);
        String json;
        try {
            json = abfragen(q);
        } catch (IOException e) {
            log.warn("Places-Suche fehlgeschlagen: {}", e.getMessage());
            throw new IllegalStateException("Google antwortet gerade nicht. Bitte gleich noch einmal.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Google antwortet gerade nicht. Bitte gleich noch einmal.");
        }
        List<Treffer> treffer = parse(json);
        if (cache.size() >= CACHE_MAX) cache.clear();
        cache.put(key, new Eintrag(jetzt, treffer));
        return new Ergebnis(true, treffer);
    }

    /** Zaehlt eine Google-Anfrage; ab MAX_JE_TAG wirft es, bis Mitternacht Berlin. */
    private synchronized void tagesdeckel(Instant jetzt) {
        LocalDate tag = jetzt.atZone(BERLIN).toLocalDate();
        if (!tag.equals(zaehlTag)) {
            zaehlTag = tag;
            heute = 0;
        }
        if (heute >= MAX_JE_TAG) throw new IllegalArgumentException(TAG_VOLL);
        heute++;
    }

    /** Text Search der Places API (New). Nur id, Name, Adresse - mehr kostet mehr. Ueberschreibbar fuer Tests. */
    protected String abfragen(String q) throws IOException, InterruptedException {
        String body = MAPPER.writeValueAsString(Map.of(
                "textQuery", q,
                "languageCode", "de",
                "regionCode", "DE",
                "maxResultCount", MAX_TREFFER));
        HttpRequest req = HttpRequest.newBuilder(URI.create(SUCHE_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", "places.id,places.displayName,places.formattedAddress")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + ": " + kurz(res.body()));
        }
        return res.body();
    }

    static List<Treffer> parse(String json) {
        List<Treffer> out = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json == null ? "{}" : json);
            for (JsonNode p : root.path("places")) {
                String id = p.path("id").asText("");
                if (!PLACE_ID.matcher(id).matches()) continue;
                String name = p.path("displayName").path("text").asText("");
                String adresse = p.path("formattedAddress").asText("");
                out.add(new Treffer(id, name, adresse, LINK + id));
                if (out.size() >= MAX_TREFFER) break;
            }
        } catch (IOException e) {
            log.warn("Places-Antwort nicht lesbar: {}", e.getMessage());
        }
        return out;
    }

    /** Der fertige Link - oder 400, wenn die ID nicht nach einer Place-ID aussieht. */
    public static String linkFuer(String placeId) {
        String id = placeId == null ? "" : placeId.trim();
        if (!PLACE_ID.matcher(id).matches()) throw new IllegalArgumentException("Das ist keine gültige Place-ID.");
        return LINK + id;
    }

    /** QR-Code des Links als PNG, 600 px, fuer Tresen-Aufsteller. */
    public static byte[] qrPng(String placeId) {
        String link = linkFuer(placeId);
        try {
            var matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 600, 600,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("QR-Code konnte nicht erzeugt werden");
        }
    }

    static String sauber(String roh) {
        if (roh == null) return "";
        String s = roh.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return s.length() > Q_MAX ? s.substring(0, Q_MAX) : s;
    }

    private static String kurz(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
