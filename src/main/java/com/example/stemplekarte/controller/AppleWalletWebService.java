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

    public AppleWalletWebService(AppleDeviceRepository deviceRepo,
                                 CustomerService customerService,
                                 ApplePassService applePass,
                                 ApnsPushService apnsPush) {
        this.deviceRepo = deviceRepo;
        this.customerService = customerService;
        this.applePass = applePass;
        this.apnsPush = apnsPush;
    }

    @PostMapping("/devices/{deviceId}/registrations/{passType}/{serial}")
    @Transactional
    public ResponseEntity<Void> register(@PathVariable String deviceId,
                                         @PathVariable String passType,
                                         @PathVariable String serial,
                                         @RequestBody Map<String, String> body,
                                         HttpServletRequest request) {
        if (!isAuthenticated(request, serial)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String pushToken = body.get("pushToken");
        if (pushToken == null) {
            return ResponseEntity.badRequest().build();
        }

        var pk = new AppleDeviceRegistration.PK(deviceId, serial);
        boolean exists = deviceRepo.existsById(pk);
        deviceRepo.save(AppleDeviceRegistration.of(deviceId, serial, pushToken));
        log.info("Apple Geraet registriert: device={} serial={} (neu={})",
                deviceId.substring(0, 8), serial, !exists);

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
        if (regs.isEmpty()) {
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
            return ResponseEntity.noContent().build();
        }
        long tag = Math.max(maxUpdated, sinceMs); // Tag darf nie zurueckspringen
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
        if (!isAuthenticated(request, serial)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        CustomerCard cc = customerService.getCustomerCardById(serial);
        byte[] pass = applePass.generatePass(cc);

        java.time.ZonedDateTime zonedDateTime = cc.getUpdatedAt().atZone(java.time.ZoneId.of("GMT"));
        String appleDateHeader = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(zonedDateTime);

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
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        deviceRepo.deleteByDeviceLibraryIdentifierAndSerialNumber(deviceId, serial);
        log.info("Apple Geraet abgemeldet: device={} serial={}",
                deviceId.substring(0, 8), serial);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/log")
    public ResponseEntity<Void> log(@RequestBody Map<String, Object> body) {
        log.warn("Apple Wallet Log: {}", body);
        return ResponseEntity.ok().build();
    }

    private boolean isAuthenticated(HttpServletRequest request, String serial) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("ApplePass ")) return false;
        String token = header.substring("ApplePass ".length()).trim();
        try {
            CustomerCard cc = customerService.getCustomerCardById(serial);
            return cc.getAuthToken().equals(token);
        } catch (Exception e) {
            return false;
        }
    }
}