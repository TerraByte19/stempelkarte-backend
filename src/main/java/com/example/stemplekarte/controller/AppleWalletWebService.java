package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.AppleDeviceRegistration;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.wallet.ApplePassService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/wallet/v1")
public class AppleWalletWebService {

    private static final Logger log = LoggerFactory.getLogger(AppleWalletWebService.class);

    private final AppleDeviceRepository deviceRepo;
    private final CustomerService customerService;
    private final ApplePassService applePass;

    public AppleWalletWebService(AppleDeviceRepository deviceRepo,
                                 CustomerService customerService,
                                 ApplePassService applePass) {
        this.deviceRepo = deviceRepo;
        this.customerService = customerService;
        this.applePass = applePass;
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
        log.info("Apple Geraet registriert: device={} serial={}",
                deviceId.substring(0, 8), serial);

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
        return ResponseEntity.ok(Map.of(
                "serialNumbers", regs.stream()
                        .map(AppleDeviceRegistration::getSerialNumber).toList(),
                "lastUpdated", String.valueOf(Instant.now().toEpochMilli())
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