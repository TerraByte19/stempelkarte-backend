package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.AppleDeviceRegistration;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.wallet.ApnsPushService;
import com.example.stemplekarte.wallet.ApplePassService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/wallet/v1")
public class AppleWalletWebService {

    private static final Logger log = LoggerFactory.getLogger(AppleWalletWebService.class);

    private final AppleDeviceRepository deviceRepo;
    private final CustomerService customerService;
    private final ApplePassService applePass;
    private final ApnsPushService apnsPush;

    private final com.example.stemplekarte.wallet.WalletDiagnose diagnose;

    public AppleWalletWebService(AppleDeviceRepository deviceRepo,
                                 CustomerService customerService,
                                 ApplePassService applePass,
                                 ApnsPushService apnsPush,
                                 com.example.stemplekarte.wallet.WalletDiagnose diagnose) {
        this.deviceRepo = deviceRepo;
        this.customerService = customerService;
        this.applePass = applePass;
        this.apnsPush = apnsPush;
        this.diagnose = diagnose;
    }

    @PostMapping("/devices/{deviceId}/registrations/{passType}/{serial}")
    @Transactional
    public ResponseEntity<Void> register(@PathVariable String deviceId,
                                         @PathVariable String passType,
                                         @PathVariable String serial,
                                         @RequestBody Map<String, String> body,
                                         HttpServletRequest request) {
        log.info("[WALLET] REGISTER-VERSUCH serial={} device={} passType={}",
                serial, kurz(deviceId), passType);

        if (!isAuthenticated(request, serial)) {
            // Passiert, wenn der Pass auf dem iPhone einen anderen authToken
            // traegt als die Karte in der DB - dann kann sich das Geraet NIE
            // anmelden und die Karte haengt fuer immer auf dem alten Stand.
            log.warn("[WALLET] REGISTER-ABGELEHNT serial={} device={} grund=AUTH_TOKEN_PASST_NICHT "
                    + "(Pass auf dem Handy ist aelter als die Karte in der DB -> Kunde muss Pass neu hinzufuegen)",
                    serial, kurz(deviceId));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String pushToken = body.get("pushToken");
        if (pushToken == null) {
            log.warn("[WALLET] REGISTER-ABGELEHNT serial={} device={} grund=KEIN_PUSHTOKEN_IM_BODY",
                    serial, kurz(deviceId));
            return ResponseEntity.badRequest().build();
        }

        var pk = new AppleDeviceRegistration.PK(deviceId, serial);
        boolean exists = deviceRepo.existsById(pk);
        deviceRepo.save(AppleDeviceRegistration.of(deviceId, serial, pushToken));
        log.info("[WALLET] REGISTER-OK serial={} device={} neu={} pushToken={}",
                serial, kurz(deviceId), !exists, maskiere(pushToken));

        // Registrierungs-Rennen bei frischen Paessen schliessen: der Kunde
        // meldet sich an, fuegt den Pass hinzu und wird oft SEKUNDEN spaeter
        // gestempelt - da ist noch kein Geraet registriert, der Push nach dem
        // Scan geht ins Leere, und danach triggert nichts mehr. Darum jetzt:
        // sobald sich ein Geraet NEU registriert, direkt einen Push an genau
        // diese Karte schicken, damit das frische Geraet den aktuellen Stand
        // holt. Erst NACH dem Commit, sonst findet pushOnce die Zeile nicht.
        if (!exists) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        try { apnsPush.notifyUpdate(serial); } catch (Exception ignored) {}
                    }
                });
            } else {
                try { apnsPush.notifyUpdate(serial); } catch (Exception ignored) {}
            }
        }

        return ResponseEntity.status(exists ? HttpStatus.OK : HttpStatus.CREATED).build();
    }

    @GetMapping("/devices/{deviceId}/registrations/{passType}")
    public ResponseEntity<Map<String, Object>> serialsForDevice(
            @PathVariable String deviceId,
            @PathVariable String passType,
            @RequestParam(value = "passesUpdatedSince", required = false) String since) {

        List<AppleDeviceRegistration> regs = deviceRepo.findByDeviceLibraryIdentifier(deviceId);
        log.info("[WALLET] POLL-START device={} since={} registrierteKarten={}",
                kurz(deviceId), since, regs.size());
        if (regs.isEmpty()) {
            // Das iPhone fragt nach Updates, wir kennen es aber gar nicht.
            // Genau der Zustand, in dem ein Kunde ewig auf alten Stempeln sitzt.
            log.warn("[WALLET] POLL-UNBEKANNTES-GERAET device={} -> iPhone fragt nach Updates, "
                    + "aber keine Registrierung in der DB", kurz(deviceId));
            return ResponseEntity.noContent().build();
        }

        // "passesUpdatedSince" ist der Wert, den wir beim letzten Aufruf als
        // "lastUpdated" zurueckgegeben haben (Epoch-Millis als String). iOS
        // schickt ihn zurueck; wir liefern nur Karten, die SEITHER geaendert
        // wurden. Fehlt/unlesbar -> 0 -> alle Karten. Das ist der eigentliche
        // Selbstheilungs-Weg, wenn ein Push verloren geht.
        long sinceMs = 0L;
        if (since != null && !since.isBlank()) {
            try { sinceMs = Long.parseLong(since.trim()); } catch (NumberFormatException ignored) {}
        }

        List<String> changed = new ArrayList<>();
        long maxUpdated = 0L;
        for (AppleDeviceRegistration reg : regs) {
            String serial = reg.getSerialNumber();
            long updated;
            try {
                CustomerCard cc = customerService.getCustomerCardById(serial);
                updated = cc.getUpdatedAt() != null ? cc.getUpdatedAt().toEpochMilli() : 0L;
            } catch (Exception e) {
                // Karte gibt es nicht mehr -> trotzdem melden, damit iOS sie
                // abfragt und ueber 404 sauber entfernt.
                changed.add(serial);
                continue;
            }
            if (updated > maxUpdated) maxUpdated = updated;
            if (updated > sinceMs) changed.add(serial);
        }

        if (changed.isEmpty()) {
            log.info("[WALLET] POLL-NICHTS-NEU device={} since={} maxUpdated={} -> 204",
                    kurz(deviceId), sinceMs, maxUpdated);
            return ResponseEntity.noContent().build();
        }
        long tag = Math.max(maxUpdated, sinceMs); // Tag darf nie zurueckspringen
        log.info("[WALLET] POLL-ANTWORT device={} since={} geaendert={} tag={}",
                kurz(deviceId), sinceMs, changed, tag);
        return ResponseEntity.ok(Map.of(
                "serialNumbers", changed,
                "lastUpdated", String.valueOf(tag)
        ));
    }

    @GetMapping("/passes/{passType}/{serial}")
    @Transactional(readOnly = true) // Hält die Hibernate-Session offen, bis die Datei generiert wurde!
    public ResponseEntity<byte[]> latestPass(@PathVariable String passType,
                                             @PathVariable String serial,
                                             HttpServletRequest request) throws Exception {
        // Diese Zeile ist der Beweis, dass das iPhone auf den Push reagiert hat.
        // Fehlt sie nach einem PUSH-OK, hat Apple den Push verworfen (Tageslimit)
        // oder das Geraet ist offline.
        long startNs = System.nanoTime();
        // Abstand zum letzten Push. Das ist der Hebel, um "Handy war offline"
        // von "Apple hat verworfen" zu trennen:
        //   wenige Sekunden  -> alles normal
        //   Minuten bis 1 h  -> Handy war offline, APNs hat nachgeliefert
        //   gar keine Zeile  -> Push kam nie an (Limit, Pass geloescht, dauerhaft offline)
        String nachPush = diagnose.seitLetztemPush(serial)
                .map(d -> d.toSeconds() + "s")
                .orElse("keinPushBekannt");
        log.info("[WALLET] PASS-ABRUF-START serial={} nachLetztemPush={} pushesHeute={} ifModifiedSince={}",
                serial, nachPush, diagnose.pushesHeute(serial),
                request.getHeader("If-Modified-Since"));

        if (!isAuthenticated(request, serial)) {
            log.warn("[WALLET] PASS-ABRUF-ABGELEHNT serial={} grund=AUTH_TOKEN_PASST_NICHT", serial);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        CustomerCard cc;
        byte[] pass;
        try {
            cc = customerService.getCustomerCardById(serial);
            pass = applePass.generatePass(cc);
        } catch (Exception e) {
            // Schlaegt der Pass-Bau fehl, bekommt iOS einen Fehler und behaelt
            // die alte Karte - ohne jede Meldung beim Kunden.
            log.error("[WALLET] PASS-ABRUF-FEHLER serial={} dauerMs={}",
                    serial, (System.nanoTime() - startNs) / 1_000_000L, e);
            throw e;
        }

        java.time.ZonedDateTime zonedDateTime = cc.getUpdatedAt().atZone(java.time.ZoneId.of("GMT"));
        String appleDateHeader = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(zonedDateTime);

        log.info("[WALLET] PASS-ABRUF-OK serial={} stempel={} bytes={} lastModified=\"{}\" dauerMs={}",
                serial, cc.getStamps(), pass.length, appleDateHeader,
                (System.nanoTime() - startNs) / 1_000_000L);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.pkpass"))
                .header("Last-Modified", appleDateHeader)
                // Kein Zwischenspeichern durch Render-Proxy / iOS-URL-Cache -
                // sonst kann eine veraltete Pass-Datei ausgeliefert werden,
                // obwohl der Stempelstand schon neu ist.
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(pass);
    }

    @DeleteMapping("/devices/{deviceId}/registrations/{passType}/{serial}")
    @Transactional
    public ResponseEntity<Void> unregister(@PathVariable String deviceId,
                                           @PathVariable String passType,
                                           @PathVariable String serial,
                                           HttpServletRequest request) {
        if (!isAuthenticated(request, serial)) {
            log.warn("[WALLET] ABMELDUNG-ABGELEHNT serial={} device={} grund=AUTH_TOKEN_PASST_NICHT",
                    serial, kurz(deviceId));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        deviceRepo.deleteByDeviceLibraryIdentifierAndSerialNumber(deviceId, serial);
        // Kommt normalerweise nur, wenn der Kunde die Karte aus der Wallet
        // loescht. Taucht das unerwartet auf, erklaert es ausbleibende Updates.
        log.warn("[WALLET] ABMELDUNG serial={} device={} -> ab jetzt kein Push mehr fuer diese Karte",
                serial, kurz(deviceId));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/log")
    public ResponseEntity<Void> log(@RequestBody Map<String, Object> body) {
        // Apples eigene Fehlermeldungen vom Geraet. Gold wert bei Pass-Problemen.
        log.warn("[WALLET] APPLE-GERAETE-LOG {}", body);
        return ResponseEntity.ok().build();
    }

    private static String kurz(String id) {
        if (id == null) return "null";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String maskiere(String s) {
        if (s == null) return "null";
        if (s.length() <= 10) return s.substring(0, Math.min(4, s.length())) + "...";
        return s.substring(0, 6) + "..." + s.substring(s.length() - 4);
    }

    private boolean isAuthenticated(HttpServletRequest request, String serial) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("ApplePass ")) {
            log.warn("[WALLET] AUTH-FEHLT serial={} header={}", serial,
                    header == null ? "(kein Authorization-Header)" : "unerwartetes Format");
            return false;
        }
        String token = header.substring("ApplePass ".length()).trim();
        try {
            CustomerCard cc = customerService.getCustomerCardById(serial);
            boolean ok = cc.getAuthToken().equals(token);
            if (!ok) {
                log.warn("[WALLET] AUTH-TOKEN-ABWEICHUNG serial={} handySchickte={} dbHat={}",
                        serial, maskiere(token), maskiere(cc.getAuthToken()));
            }
            return ok;
        } catch (Exception e) {
            log.warn("[WALLET] AUTH-KARTE-UNBEKANNT serial={} grund={}", serial, e.getMessage());
            return false;
        }
    }
}