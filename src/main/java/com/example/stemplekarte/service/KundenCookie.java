package com.example.stemplekarte.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Merkt sich im Kundenhandy, wer der Kunde ist.
 *
 * <p>Der NFC-Tag am Tresen traegt nur die Karte des Ladens - er kann gar
 * nicht wissen, wessen Handy ihn gerade liest. Die Kunden-ID muss also vom
 * Handy kommen. Sie wird gesetzt, sobald der Kunde seine Kartenseite
 * oeffnet, und beim NFC-Tippen wieder gelesen.
 *
 * <p>Signiert mit HMAC-SHA256: ein Cookie ist im Browser frei editierbar,
 * eine unsignierte Kunden-ID waere eine Einladung, sich auf fremde Karten
 * zu stempeln. Ohne gueltige Signatur gilt das Cookie als nicht vorhanden.
 */
public final class KundenCookie {

    private static final Logger log = LoggerFactory.getLogger(KundenCookie.class);

    /** Anfang des Cookie-Namens; dahinter steht die Karte (siehe name). */
    public static final String NAME = "sk_kunde";

    /**
     * Ein Cookie je Karte. Ein Kunde ohne Anmeldung hat bei jedem Laden
     * eine eigene Kunden-ID; mit EINEM Cookie fuer alles ueberschriebe die
     * zuletzt geoeffnete Karte die anderen, und das Antippen im ersten
     * Laden liefe auf eine neue, leere Karte statt auf die in der Wallet.
     */
    public static String name(String cardId) {
        String sauber = cardId == null ? "" : cardId.replaceAll("[^A-Za-z0-9_-]", "");
        return NAME + "_" + sauber;
    }

    /**
     * Darf diese Anfrage eine BESTEHENDE Zuordnung auf einen anderen
     * Kunden umstellen?
     *
     * <p>Nicht, wenn der Aufruf von einer fremden Seite kommt. Sonst
     * schickt jemand einem Stammkunden den Link zu seiner EIGENEN Karte;
     * der Kunde tippt darauf, und ab da laufen seine Stempel am Tresen auf
     * die Karte des Absenders. Aus der Wallet, aus dem Verlauf, aus einer
     * Mail-App oder von unserer eigenen Anmeldeseite heisst der Kopf
     * "none" bzw. "same-origin" - das bleibt erlaubt. Fehlt der Kopf (alte
     * Browser), wird umgestellt, wie bei darfSetzen.
     */
    public static boolean darfWechseln(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        return site == null || site.isBlank() || !"cross-site".equalsIgnoreCase(site.trim());
    }

    /** Ein Jahr. Stammkunden kommen unregelmaessig - kuerzer waere laestig. */
    private static final int MAX_AGE_SEKUNDEN = 365 * 24 * 60 * 60;

    private KundenCookie() {}

    /**
     * Darf diese Anfrage die Kunden-ID im Handy setzen?
     *
     * <p>Nur wenn der Browser die Seite wirklich als Seite oeffnet. Sonst
     * genuegt ein {@code <img src="https://.../karte/CUST-ANGREIFER/CARD-X">}
     * auf einer beliebigen fremden Seite: der Browser laedt die Adresse im
     * Hintergrund, nimmt das Set-Cookie an, und ab da schreibt jedes Antippen
     * am Tresen die Stempel des Besuchers auf die Karte des Angreifers. Beim
     * Bild steht in {@code Sec-Fetch-Dest} dann "image", bei einem echten
     * Seitenaufruf "document".
     *
     * <p>Fehlt der Kopf ganz (alte Browser), wird gesetzt - sonst bekaemen
     * genau die Geraete nie ein Cookie, die auch sonst am wenigsten koennen.
     */
    public static boolean darfSetzen(HttpServletRequest request) {
        String dest = request.getHeader("Sec-Fetch-Dest");
        return dest == null || dest.isBlank() || "document".equals(dest);
    }

    /**
     * Setzt das Cookie auf die Antwort. {@code secure} nur bei HTTPS, sonst
     * verwirft der Browser es beim lokalen Testen auf http://localhost.
     */
    public static void setzen(HttpServletResponse response, String cardId, String customerId,
                              String secret, boolean secure) {
        String wert = customerId + "." + signatur(customerId, secret);

        // Cookie selbst zusammenbauen statt Cookie#setAttribute: SameSite
        // kennt die Servlet-API nicht, und ohne SameSite=Lax schickt Safari
        // das Cookie beim NFC-Aufruf womoeglich nicht mit.
        StringBuilder sb = new StringBuilder();
        sb.append(name(cardId)).append('=').append(wert);
        sb.append("; Max-Age=").append(MAX_AGE_SEKUNDEN);
        sb.append("; Path=/");
        sb.append("; HttpOnly");
        sb.append("; SameSite=Lax");
        if (secure) sb.append("; Secure");

        response.addHeader("Set-Cookie", sb.toString());
    }

    /** Kunden-ID aus dem Cookie, wenn Signatur stimmt. Sonst leer. */
    public static Optional<String> lesen(HttpServletRequest request, String cardId, String secret) {
        String name = name(cardId);
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();

        for (Cookie c : cookies) {
            if (!name.equals(c.getName())) continue;

            String wert = c.getValue();
            if (wert == null) continue;

            int punkt = wert.lastIndexOf('.');
            if (punkt <= 0 || punkt == wert.length() - 1) continue;

            String customerId = wert.substring(0, punkt);
            String signatur = wert.substring(punkt + 1);

            if (gueltig(customerId, signatur, secret)) {
                return Optional.of(customerId);
            }
            log.warn("[NFC] Kunden-Cookie mit falscher Signatur verworfen");
        }
        return Optional.empty();
    }

    /** Loescht das Cookie - fuer den Fall, dass die Kunden-ID nicht mehr existiert. */
    public static void loeschen(HttpServletResponse response, String cardId) {
        response.addHeader("Set-Cookie", name(cardId) + "=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax");
    }

    static boolean gueltig(String customerId, String signatur, String secret) {
        byte[] erwartet = signatur(customerId, secret).getBytes(StandardCharsets.UTF_8);
        byte[] gegeben = signatur.getBytes(StandardCharsets.UTF_8);
        // Zeitkonstanter Vergleich: ein frueh abbrechender equals() verraet
        // ueber die Antwortzeit, wie viele Zeichen schon stimmen.
        return MessageDigest.isEqual(erwartet, gegeben);
    }

    static String signatur(String customerId, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] roh = mac.doFinal(customerId.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(roh);
        } catch (Exception e) {
            // Fehlt HmacSHA256, ist die JVM kaputt - nicht sinnvoll abfangbar.
            throw new IllegalStateException("Kunden-Cookie laesst sich nicht signieren", e);
        }
    }
}
