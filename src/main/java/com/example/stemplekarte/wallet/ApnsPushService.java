package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.AppleDeviceRegistration;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class ApnsPushService {

    private static final Logger log = LoggerFactory.getLogger(ApnsPushService.class);

    private final AppProperties props;
    private volatile String cachedJwt;
    private volatile long cachedJwtTime = 0;
    private static final long JWT_TTL_MS = 50 * 60 * 1000L; // 50 Min
    private final AppleDeviceRepository deviceRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    // Apple limitiert Pass-Pushes auf ~3 pro Tag pro Karte. Darueber hinaus
    // verwirft der Wallet-Dienst stille Pushes komplett (APNs meldet trotzdem
    // "200 OK") und holt die Karte GAR NICHT mehr - das war der eigentliche
    // Fehler: die frueheren 3 Pushes pro Scan (sofort + 25s + 90s) haben das
    // Tageslimit sofort verbraucht. Jetzt: EIN Push pro Scan. Der Notnagel bei
    // verlorenem Push ist iOS' eigener Poll gegen die webServiceURL
    // (siehe AppleWalletWebService#serialsForDevice - liefert jetzt zuverlaessig
    // die geaenderten Karten).
    private static final long[] RETRY_DELAYS_SEC = {};
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "apns-retry");
                t.setDaemon(true);
                return t;
            });

    private final WalletDiagnose diagnose;

    public ApnsPushService(AppProperties props, AppleDeviceRepository deviceRepo,
                           WalletDiagnose diagnose) {
        this.props = props;
        this.deviceRepo = deviceRepo;
        this.diagnose = diagnose;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Async
    public void notifyUpdate(String serialNumber) {
        if (!props.apns().enabled()) {
            // Frueher DEBUG -> unsichtbar. Das ist ein Totalausfall des Wallet-
            // Updates und muss im Log stehen.
            log.warn("[WALLET] PUSH-ABBRUCH serial={} grund=APNS_DEAKTIVIERT "
                    + "(stempelkarte.apns.enabled=false / ENV APNS_ENABLED nicht gesetzt)", serialNumber);
            return;
        }

        log.info("[WALLET] PUSH-START serial={}", serialNumber);

        // 1. Push sofort ...
        pushOnce(serialNumber);

        // 2. ... und ein-, zweimal kurz nachschieben, falls der erste verloren geht.
        for (long delay : RETRY_DELAYS_SEC) {
            try {
                retryScheduler.schedule(() -> {
                    try {
                        pushOnce(serialNumber);
                    } catch (Exception e) {
                        log.warn("APNs Nachschlag-Push fehlgeschlagen fuer {}: {}", serialNumber, e.getMessage());
                    }
                }, delay, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("APNs Nachschlag konnte nicht geplant werden: {}", e.getMessage());
            }
        }
    }

    @Transactional
    public void pushOnce(String serialNumber) {
        List<AppleDeviceRegistration> devices = deviceRepo.findBySerialNumber(serialNumber);
        if (devices.isEmpty()) {
            // Frueher DEBUG -> unsichtbar. Genau dieser Fall ist der Verdacht
            // bei "Stempel im System, aber nicht auf dem iPhone": das Geraet hat
            // sich nie unter /wallet/v1/devices/... angemeldet, der Push kann
            // also gar nicht ankommen.
            log.warn("[WALLET] PUSH-ABBRUCH serial={} grund=KEIN_GERAET_REGISTRIERT "
                    + "(iPhone hat sich nie bei /wallet/v1/devices/... gemeldet)", serialNumber);
            return;
        }
        log.info("[WALLET] PUSH-GERAETE serial={} anzahl={}", serialNumber, devices.size());

        String jwt;
        try {
            jwt = getProviderToken();
        } catch (Exception e) {
            log.error("[WALLET] PUSH-ABBRUCH serial={} grund=JWT_FEHLER keyPath={} keyIdGesetzt={} teamIdGesetzt={}",
                    serialNumber, props.apns().authKeyPath(),
                    props.apns().keyId() != null && !props.apns().keyId().isBlank(),
                    props.apns().teamId() != null && !props.apns().teamId().isBlank(), e);
            return;
        }

        String host = props.apns().useSandbox()
                ? "https://api.sandbox.push.apple.com"
                : "https://api.push.apple.com";
        log.info("[WALLET] PUSH-KONFIG serial={} host={} topic={} sandbox={}",
                serialNumber, host, props.apple().passTypeIdentifier(), props.apns().useSandbox());

        // apns-expiration: APNs haelt den Push bis zu 1h vor und stellt erneut zu,
        // falls das Geraet gerade offline war (0 = sofort verwerfen - das war der Bug).
        String expiration = String.valueOf(Instant.now().getEpochSecond() + 3600);
        // collapse-id: mehrere Pushes fuer dieselbe Karte werden auf dem Geraet
        // zu einem zusammengefasst (max 64 Byte - CC-... ist kurz genug).
        String collapseId = serialNumber.length() > 64 ? serialNumber.substring(0, 64) : serialNumber;

        for (AppleDeviceRegistration device : devices) {
            try {
                String body = mapper.writeValueAsString(Map.of("aps", Map.of()));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(host + "/3/device/" + device.getPushToken()))
                        .header("authorization", "bearer " + jwt)
                        .header("apns-topic", props.apple().passTypeIdentifier())
                        .header("apns-push-type", "background")
                        .header("apns-priority", "5")
                        .header("apns-expiration", expiration)
                        .header("apns-collapse-id", collapseId)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                long t0 = System.nanoTime();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                long dauerMs = (System.nanoTime() - t0) / 1_000_000L;
                String device8 = device.getDeviceLibraryIdentifier().substring(0, 8);
                // apns-id: Apples eigene Vorgangsnummer. Damit laesst sich ein
                // einzelner Push spaeter zweifelsfrei wiederfinden.
                String apnsId = resp.headers().firstValue("apns-id").orElse("-");
                if (resp.statusCode() == 200) {
                    // ACHTUNG: 200 heisst nur "APNs hat es angenommen". Bei
                    // ueberschrittenem Tageslimit (~3 Pass-Pushes/Tag/Karte)
                    // verwirft der Wallet-Dienst den Push trotzdem still. Ob das
                    // iPhone wirklich reagiert hat, zeigt erst die spaetere
                    // PASS-ABRUF-Zeile zur selben serial.
                    int heute = diagnose.pushGezaehlt(serialNumber);
                    log.info("[WALLET] PUSH-OK serial={} device={} apnsId={} dauerMs={} pushesHeute={}",
                            serialNumber, device8, apnsId, dauerMs, heute);
                    if (heute > 3) {
                        log.warn("[WALLET] PUSH-LIMIT-VERDACHT serial={} pushesHeute={} -> Apple erlaubt nur "
                                + "~3 Pass-Pushes/Tag/Karte und verwirft weitere still (meldet trotzdem 200). "
                                + "Bleibt jetzt der PASS-ABRUF aus, ist DAS die Ursache - nicht das Handy.",
                                serialNumber, heute);
                    }
                } else {
                    log.warn("[WALLET] PUSH-FEHLER serial={} device={} status={} apnsId={} dauerMs={} body={}",
                            serialNumber, device8, resp.statusCode(), apnsId, dauerMs, resp.body());
                    if (resp.statusCode() == 410
                            || resp.body().contains("BadDeviceToken")
                            || resp.body().contains("Unregistered")) {
                        deviceRepo.deleteByPushToken(device.getPushToken());
                        log.warn("[WALLET] PUSH-TOKEN-TOT serial={} device={} -> Registrierung geloescht, "
                                + "Karte bekommt ohne erneutes Hinzufuegen NIE wieder ein Update",
                                serialNumber, device8);
                    }
                }
            } catch (Exception e) {
                log.error("[WALLET] PUSH-EXCEPTION serial={} device={}",
                        serialNumber, device.getDeviceLibraryIdentifier().substring(0, 8), e);
            }
        }
    }

    private String buildProviderToken() throws Exception {
        byte[] keyBytes = Files.readAllBytes(Path.of(props.apns().authKeyPath()));
        String pem = new String(keyBytes)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        PrivateKey privateKey = KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(decoded));

        return Jwts.builder()
                .header().add("kid", props.apns().keyId()).and()
                .issuer(props.apns().teamId())
                .issuedAt(new Date())
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
    }

    private synchronized String getProviderToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedJwt == null || (now - cachedJwtTime) > JWT_TTL_MS) {
            cachedJwt = buildProviderToken();
            cachedJwtTime = now;
            log.info("Neuer APNs Provider-Token generiert");
        }
        return cachedJwt;
    }

}