package com.example.stemplekarte.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * "Mit Apple anmelden" beim Kartenholen, Gegenstueck zu {@link GoogleAnmeldung}.
 * Apples Skript oeffnet ein Fenster, der Kunde bestaetigt, wir bekommen ein
 * ID-Token (JWT, RS256) und pruefen es selbst: Signatur gegen Apples
 * oeffentliche Schluessel, Herausgeber, Zielgruppe (unsere Services-ID),
 * Ablauf, bestaetigte Adresse.
 *
 * Aktiv nur mit APPLE_LOGIN_CLIENT_ID (Services-ID aus dem Developer-Konto,
 * Sign in with Apple aktiv, Domain und Ruecksprung-Adresse eingetragen).
 * Ohne die Variable zeigt die Anmeldeseite keinen Apple-Knopf.
 *
 * Besonderheit: der Name steht nicht im Token. Apples Fenster liefert ihn
 * nur beim allerersten Mal mit, die Seite schickt ihn deshalb separat.
 * Die Adresse kann ein Weiterleitungs-Postfach sein (...@privaterelay.appleid.com);
 * Mails dorthin kommen nur an, wenn unsere Absender-Domain bei Apple eingetragen ist.
 */
@Service
public class AppleAnmeldung {

    private static final Logger log = LoggerFactory.getLogger(AppleAnmeldung.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String HERAUSGEBER = "https://appleid.apple.com";
    static final String SCHLUESSEL_URL = "https://appleid.apple.com/auth/keys";
    static final String FEHLER = "Apple-Anmeldung fehlgeschlagen";
    static final int MAX_JE_IP = 20;
    static final int NAME_MAX = 100;

    /** Schluessel gelten als frisch; danach beim naechsten Token neu laden. */
    static final Duration SCHLUESSEL_FRISCH = Duration.ofHours(24);
    /** Nie oefter laden - sonst waere jedes falsche Token eine Anfrage an Apple. */
    static final Duration SCHLUESSEL_PAUSE = Duration.ofMinutes(1);

    public record Profil(String email, String name) {}

    private final String clientId;
    private final Drossel drossel = new Drossel(MAX_JE_IP, Duration.ofMinutes(10));
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private Map<String, PublicKey> schluessel = Map.of();
    private Instant geladenAm = Instant.EPOCH;
    private Instant versuchtAm = Instant.EPOCH;

    public AppleAnmeldung(@Value("${stempelkarte.apple.login-client-id:}") String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public boolean aktiv() {
        return !clientId.isBlank();
    }

    public String clientId() {
        return clientId;
    }

    /** Wirft bei zu vielen Versuchen von derselben Adresse. */
    public void drossel(String ip, Instant jetzt) {
        drossel.pruefe(ip, jetzt);
    }

    /**
     * IllegalArgumentException (400) mit Klartext, wenn das Token nicht passt.
     * nameVomKunden: was Apples Fenster geliefert hat, darf fehlen.
     */
    public Profil pruefe(String idToken, String nameVomKunden) {
        if (!aktiv()) throw new IllegalArgumentException("Anmeldung mit Apple ist nicht eingerichtet");
        if (idToken == null || idToken.isBlank() || idToken.length() > 4096) {
            throw new IllegalArgumentException(FEHLER);
        }
        Claims c;
        try {
            c = Jwts.parser()
                    .keyLocator(new SchluesselSucher())
                    .requireIssuer(HERAUSGEBER)
                    .requireAudience(clientId)
                    .clockSkewSeconds(60)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
        } catch (Exception e) {
            log.info("[APPLE] Token abgelehnt: {}", e.getMessage());
            throw new IllegalArgumentException(FEHLER);
        }
        if (!bestaetigt(c.get("email_verified"))) {
            throw new IllegalArgumentException("Deine Apple-Adresse ist nicht bestätigt");
        }
        String email = c.get("email") == null ? "" : String.valueOf(c.get("email")).trim();
        if (email.isBlank() || !email.contains("@")) throw new IllegalArgumentException(FEHLER);
        String name = sauber(nameVomKunden);
        if (name.isBlank()) {
            int at = email.indexOf('@');
            name = at > 0 ? email.substring(0, at) : email;
        }
        return new Profil(email, name);
    }

    /** Apple schickt "true" als Text oder als Wahrheitswert. */
    static boolean bestaetigt(Object v) {
        return v != null && "true".equalsIgnoreCase(String.valueOf(v).trim());
    }

    static String sauber(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").trim();
        return t.length() > NAME_MAX ? t.substring(0, NAME_MAX).trim() : t;
    }

    /** Sucht den Schluessel zum kid im Token-Kopf. */
    private class SchluesselSucher implements Locator<Key> {
        @Override
        public Key locate(Header header) {
            String kid = header instanceof ProtectedHeader p ? p.getKeyId() : null;
            if (kid == null || kid.isBlank()) throw new IllegalArgumentException("kid fehlt");
            Key k = schluesselFuer(kid);
            if (k == null) throw new IllegalArgumentException("unbekannter Schluessel " + kid);
            return k;
        }
    }

    /**
     * Aus dem Cache; bei unbekanntem kid (Apple wechselt Schluessel) oder
     * nach 24 Stunden neu laden, aber hoechstens einmal je Minute.
     */
    synchronized Key schluesselFuer(String kid) {
        Instant jetzt = Instant.now();
        PublicKey k = schluessel.get(kid);
        boolean alt = geladenAm.plus(SCHLUESSEL_FRISCH).isBefore(jetzt);
        boolean darf = versuchtAm.plus(SCHLUESSEL_PAUSE).isBefore(jetzt);
        if ((k == null || alt) && darf) {
            versuchtAm = jetzt;
            Map<String, PublicKey> neu = laden();
            if (neu != null && !neu.isEmpty()) {
                schluessel = neu;
                geladenAm = jetzt;
                k = neu.get(kid);
            }
        }
        return k;
    }

    /** Apples oeffentliche Schluessel, kid -> Key; null bei Fehler. Fuer Tests ueberschreibbar. */
    protected Map<String, PublicKey> laden() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SCHLUESSEL_URL))
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("[APPLE] Schluessel laden: {}", res.statusCode());
                return null;
            }
            return parse(res.body());
        } catch (Exception e) {
            log.warn("[APPLE] Schluessel laden fehlgeschlagen: {}", e.getMessage());
            return null;
        }
    }

    /** JWKS ({"keys":[{kty,kid,n,e}...]}) zu RSA-Schluesseln. */
    static Map<String, PublicKey> parse(String json) throws Exception {
        Map<String, PublicKey> m = new HashMap<>();
        for (JsonNode k : MAPPER.readTree(json).path("keys")) {
            if (!"RSA".equals(k.path("kty").asText())) continue;
            String kid = k.path("kid").asText("");
            if (kid.isBlank()) continue;
            BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("n").asText()));
            BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("e").asText()));
            m.put(kid, KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
        }
        return Map.copyOf(m);
    }
}
