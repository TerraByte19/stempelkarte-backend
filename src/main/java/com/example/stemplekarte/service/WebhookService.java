package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.WebhookEreignis;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.WebhookEreignisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Webhooks: Ereignisse (Stempel, Anmeldung, Punkte) landen in einer
 * Ausgangstabelle - in derselben Transaktion wie das Ereignis selbst - und
 * der Laeufer stellt sie zu: POST mit JSON, signiert mit HMAC-SHA256 ueber
 * das Geheimnis des Ladens. Antwortet die Gegenseite nicht mit 2xx, wird
 * mit wachsendem Abstand wiederholt, nach MAX_VERSUCHE ist Schluss.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public static final int MAX_VERSUCHE = 5;
    static final Duration[] WARTEN = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(12)};
    static final Duration TIMEOUT = Duration.ofSeconds(8);
    public static final int URL_MAX = 500;

    /** Austauschbar fuer den Test: liefert den HTTP-Status, wirft bei Netzfehlern. */
    interface Sender {
        int sende(String url, String body, Map<String, String> kopf) throws Exception;
    }

    /** Austauschbar fuer den Test: Hostname -> Adressen. */
    interface Aufloeser {
        java.net.InetAddress[] aufloesen(String host) throws java.net.UnknownHostException;
    }

    private final WebhookEreignisRepository repo;
    private final ShopRepository shopRepo;
    private final ObjectMapper json;
    private final SecureRandom zufall = new SecureRandom();
    private Sender sender;
    private Aufloeser aufloeser = java.net.InetAddress::getAllByName;

    public WebhookService(WebhookEreignisRepository repo, ShopRepository shopRepo, ObjectMapper json) {
        this.repo = repo;
        this.shopRepo = shopRepo;
        this.json = json;
        // Keine Weiterleitungen: sonst koennte eine harmlose Adresse auf ein
        // internes Ziel umleiten, nachdem die Pruefung durch ist.
        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.sender = (url, body, kopf) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            kopf.forEach(b::header);
            return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        };
    }

    void sender(Sender sender) { this.sender = sender; }
    void aufloeser(Aufloeser aufloeser) { this.aufloeser = aufloeser; }

    // ── Zieladresse pruefen (SSRF) ──────────────────────────────────────

    /**
     * Der Server ruft eine Adresse auf, die der Laden eingetragen hat. Darum:
     * nur https, kein Benutzername in der URL, ein echter Hostname, und der
     * darf auf keine interne Adresse zeigen (localhost, 10.x, 192.168.x,
     * 169.254.x, fd00::, ...). Wird beim Einrichten UND vor jedem Versand
     * geprueft - ein Hostname kann spaeter woanders hinzeigen.
     */
    void pruefeZiel(String url) {
        URI u;
        try {
            u = new URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Bitte eine gültige https-Adresse eintragen");
        }
        if (!"https".equalsIgnoreCase(u.getScheme())) {
            throw new IllegalArgumentException("Bitte eine https-Adresse eintragen");
        }
        String host = u.getHost();
        if (host == null || host.isBlank() || u.getUserInfo() != null) {
            throw new IllegalArgumentException("Bitte eine gültige https-Adresse eintragen");
        }
        java.net.InetAddress[] adressen;
        try {
            adressen = aufloeser.aufloesen(host);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("Die Adresse ist nicht erreichbar (Hostname unbekannt)");
        }
        if (adressen == null || adressen.length == 0) {
            throw new IllegalArgumentException("Die Adresse ist nicht erreichbar (Hostname unbekannt)");
        }
        for (java.net.InetAddress a : adressen) {
            if (intern(a)) {
                throw new IllegalArgumentException("Interne Adressen sind nicht erlaubt");
            }
        }
    }

    static boolean intern(java.net.InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isSiteLocalAddress()
                || a.isLinkLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        // IPv6 unique local (fc00::/7) und IPv4-gemappte Adressen (::ffff:10.0.0.1)
        if (b.length == 16) {
            if ((b[0] & 0xfe) == 0xfc) return true;
            boolean gemappt = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { gemappt = false; break; }
            if (gemappt && b[10] == (byte) 0xff && b[11] == (byte) 0xff) {
                try {
                    return intern(java.net.InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (java.net.UnknownHostException e) {
                    return true;
                }
            }
        }
        // IPv4: 100.64.0.0/10 (Carrier-NAT, auch Render-intern) und 0.0.0.0/8
        if (b.length == 4) {
            int erstes = b[0] & 0xff, zweites = b[1] & 0xff;
            if (erstes == 0) return true;
            if (erstes == 100 && zweites >= 64 && zweites <= 127) return true;
        }
        return false;
    }

    // ── Ereignis melden ─────────────────────────────────────────────────

    /**
     * Schreibt das Ereignis in die Ausgangstabelle, wenn der Laden einen
     * Webhook hat. Laeuft in der Transaktion des Aufrufers. Ein Fehler beim
     * Bauen des JSON darf den Stempel nicht kosten - deshalb gefangen.
     */
    public void melde(Shop shop, String typ, Map<String, Object> daten) {
        if (shop == null || shop.getWebhookUrl() == null || shop.getWebhookUrl().isBlank()) return;
        try {
            Instant jetzt = Instant.now();
            String id = "WH-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("typ", typ);
            body.put("zeit", jetzt.toString());
            body.put("shopId", shop.getId());
            body.put("daten", daten != null ? daten : Map.of());
            String payload = json.writeValueAsString(body);
            if (payload.length() > WebhookEreignis.PAYLOAD_MAX) {
                log.warn("[WEBHOOK] Laden {}: Ereignis {} zu gross, verworfen", shop.getId(), typ);
                return;
            }
            repo.save(WebhookEreignis.neu(id, shop.getId(), typ, payload, jetzt));
        } catch (Exception e) {
            log.warn("[WEBHOOK] Laden {}: Ereignis {} nicht gespeichert: {}", shop.getId(), typ, e.getMessage());
        }
    }

    /** Kleine Hilfe fuer Aufrufer: Schluessel-Wert-Paare, null-Werte werden weggelassen. */
    public static Map<String, Object> daten(Object... paare) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < paare.length; i += 2) {
            if (paare[i + 1] != null) m.put(String.valueOf(paare[i]), paare[i + 1]);
        }
        return m;
    }

    // ── Laeufer ─────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 20_000, initialDelay = 60_000)
    public void lauf() {
        try {
            lauf(Instant.now());
        } catch (Exception e) {
            log.warn("[WEBHOOK] Lauf abgebrochen: {}", e.getMessage());
        }
    }

    /** Paketsichtbar mit Zeitpunkt. Gibt die Zahl der zugestellten Ereignisse zurueck. */
    int lauf(Instant jetzt) {
        int zugestellt = 0;
        for (WebhookEreignis e : repo.findTop50ByStatusAndNaechsterVersuchLessThanEqualOrderByNaechsterVersuchAsc(
                WebhookEreignis.OFFEN, jetzt)) {
            Shop shop = shopRepo.findById(e.getShopId()).orElse(null);
            if (shop == null || shop.getWebhookUrl() == null || shop.getWebhookUrl().isBlank()) {
                e.verworfen("Webhook entfernt");
                repo.save(e);
                continue;
            }
            // Zieladresse vor JEDEM Versand pruefen: der Hostname koennte
            // inzwischen auf eine interne Adresse zeigen.
            try {
                pruefeZiel(shop.getWebhookUrl());
            } catch (IllegalArgumentException ex) {
                e.verworfen("Adresse nicht erlaubt: " + ex.getMessage());
                repo.save(e);
                log.warn("[WEBHOOK] Laden {}: {} verworfen, {}", shop.getId(), e.getId(), ex.getMessage());
                continue;
            }
            String antwort;
            boolean ok;
            try {
                int status = sender.sende(shop.getWebhookUrl(), e.getPayload(), kopf(shop, e));
                ok = status >= 200 && status < 300;
                antwort = "HTTP " + status;
            } catch (Exception ex) {
                // Kein roher Fehlertext in die Datenbank und ins Dashboard.
                ok = false;
                antwort = "Fehler: keine Verbindung";
                log.debug("[WEBHOOK] Laden {}: {} {}", shop.getId(), e.getId(), ex.toString());
            }
            if (ok) {
                e.gesendet(jetzt, antwort);
                zugestellt++;
            } else {
                int versuch = e.getVersuche() + 1;
                boolean letzter = versuch >= MAX_VERSUCHE;
                e.fehlgeschlagen(letzter ? null : jetzt.plus(WARTEN[Math.min(versuch - 1, WARTEN.length - 1)]),
                        antwort, letzter);
                log.info("[WEBHOOK] Laden {}: {} Versuch {} {}{}", shop.getId(), e.getId(), versuch, antwort,
                        letzter ? ", aufgegeben" : "");
            }
            repo.save(e);
        }
        return zugestellt;
    }

    Map<String, String> kopf(Shop shop, WebhookEreignis e) {
        Map<String, String> k = new LinkedHashMap<>();
        k.put("Content-Type", "application/json; charset=utf-8");
        k.put("User-Agent", "Stampit-Webhook/1");
        k.put("X-Stampit-Event", e.getTyp());
        k.put("X-Stampit-Delivery", e.getId());
        k.put("X-Stampit-Signature", "sha256=" + signatur(shop.getWebhookSecret(), e.getPayload()));
        return k;
    }

    public static String signatur(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((secret != null ? secret : "").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ── Einrichtung ─────────────────────────────────────────────────────

    public record Zustellung(String id, String typ, String status, int versuche, String erstellt, String antwort) {}
    /** secret nur direkt nach dem Einrichten, sonst null. */
    public record Stand(String url, String secret, boolean aktiv, long offen, long fehler, List<Zustellung> letzte) {}

    public Stand stand(Shop shop) {
        return stand(shop, null);
    }

    private Stand stand(Shop shop, String secret) {
        List<Zustellung> letzte = repo.findTop20ByShopIdOrderByErstelltAmDesc(shop.getId()).stream()
                .map(e -> new Zustellung(e.getId(), e.getTyp(), e.getStatus(), e.getVersuche(),
                        e.getErstelltAm().toString(), e.getAntwort()))
                .toList();
        boolean aktiv = shop.getWebhookUrl() != null && !shop.getWebhookUrl().isBlank();
        return new Stand(shop.getWebhookUrl(), secret, aktiv,
                repo.countByShopIdAndStatus(shop.getId(), WebhookEreignis.OFFEN),
                repo.countByShopIdAndStatus(shop.getId(), WebhookEreignis.FEHLER), letzte);
    }

    /** URL setzen (https, keine interne Adresse). Ein neues Geheimnis entsteht beim ersten Einrichten oder auf Wunsch. */
    @Transactional
    public Stand einrichten(String shopId, String urlRoh, boolean neuesGeheimnis) {
        String url = urlRoh == null ? "" : urlRoh.trim();
        if (url.isEmpty() || url.length() > URL_MAX || url.chars().anyMatch(Character::isWhitespace)
                || url.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Bitte eine gültige https-Adresse eintragen");
        }
        pruefeZiel(url);
        Shop shop = shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
        if (!shop.isApiFreigeschaltet()) throw new IllegalStateException(ApiKeyService.NICHT_FREIGESCHALTET);
        String secret = null;
        if (neuesGeheimnis || shop.getWebhookSecret() == null) {
            byte[] b = new byte[32];
            zufall.nextBytes(b);
            secret = HexFormat.of().formatHex(b);
        }
        shop.webhookSetzen(url, secret != null ? secret : shop.getWebhookSecret());
        shopRepo.save(shop);
        log.info("[WEBHOOK] Laden {}: eingerichtet", shopId);
        return stand(shop, secret);
    }

    @Transactional
    public Stand entfernen(String shopId) {
        Shop shop = shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
        shop.webhookEntfernen();
        shopRepo.save(shop);
        log.info("[WEBHOOK] Laden {}: entfernt", shopId);
        return stand(shop, null);
    }

    /** Ein Test-Ereignis in die Ausgangstabelle; der Laeufer stellt es gleich zu. */
    @Transactional
    public Stand teste(String shopId) {
        Shop shop = shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
        if (shop.getWebhookUrl() == null) throw new IllegalArgumentException("Erst eine Adresse eintragen");
        melde(shop, "test", daten("hallo", "Stampit", "laden", shop.getName()));
        return stand(shop, null);
    }
}
