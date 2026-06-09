package com.example.stemplekarte.controller;

import com.example.stemplekarte.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * DSGVO-Endpunkte. Liegt bewusst unter /api/admin, damit die bestehende
 * Security-Regel (/api/admin/** -> ROLE_ADMIN) automatisch greift und nur
 * der Plattform-Admin Loeschungen ausfuehren kann.
 */
@Tag(name = "GDPR", description = "DSGVO: Loeschung personenbezogener Daten")
@RestController
@RequestMapping("/api/admin")
public class GdprController {

    private final CustomerService customerService;

    public GdprController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @Operation(summary = "Kunden + alle Karten + Apple-Registrierungen loeschen (Recht auf Loeschung)")
    @DeleteMapping("/customers/{customerId}")
    public ResponseEntity<Map<String, String>> deleteCustomer(@PathVariable String customerId) {
        customerService.deleteCustomerData(customerId);
        return ResponseEntity.ok(Map.of("message", "Kundendaten geloescht"));
    }
}