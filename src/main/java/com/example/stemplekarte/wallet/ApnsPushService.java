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
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

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

    public ApnsPushService(AppProperties props, AppleDeviceRepository deviceRepo) {
        this.props = props;
        this.deviceRepo = deviceRepo;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Async
    @Transactional
    public void notifyUpdate(String serialNumber) {
        if (!props.apns().enabled()) {
            log.debug("APNs deaktiviert. Kein Push fuer {}.", serialNumber);
            return;
        }

        List<AppleDeviceRegistration> devices = deviceRepo.findBySerialNumber(serialNumber);
        if (devices.isEmpty()) {
            log.debug("Keine registrierten Apple Geraete fuer {}.", serialNumber);
            return;
        }

        String jwt;
        try {
            jwt = getProviderToken();
        } catch (Exception e) {
            log.error("APNs JWT konnte nicht erstellt werden", e);
            return;
        }

        String host = props.apns().useSandbox()
                ? "https://api.sandbox.push.apple.com"
                : "https://api.push.apple.com";

        for (AppleDeviceRegistration device : devices) {
            try {
                String body = mapper.writeValueAsString(Map.of("aps", Map.of()));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(host + "/3/device/" + device.getPushToken()))
                        .header("authorization", "bearer " + jwt)
                        .header("apns-topic", props.apple().passTypeIdentifier())
                        .header("apns-push-type", "background")
                        .header("apns-priority", "5")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    log.info("APNs Push OK fuer Geraet {} (Pass {})",
                            device.getDeviceLibraryIdentifier().substring(0, 8), serialNumber);
                } else {
                    log.warn("APNs Push fehlgeschlagen ({}): {}", resp.statusCode(), resp.body());
                    if (resp.statusCode() == 410
                            || resp.body().contains("BadDeviceToken")
                            || resp.body().contains("Unregistered")) {
                        deviceRepo.deleteByPushToken(device.getPushToken());
                        log.info("Toter APNs Token entfernt fuer Geraet {}",
                                device.getDeviceLibraryIdentifier().substring(0, 8));
                    }
                }
            } catch (Exception e) {
                log.error("APNs Push Exception", e);
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