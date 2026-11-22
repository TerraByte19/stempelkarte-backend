package com.example.stemplekarte.controller;

import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.FilialGeraeteService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Unterkonto (Tarif Kette): die Scanner-Geraete der EIGENEN Filiale. Die
 * Filiale kommt aus dem Login. {kennung} ist die abgeleitete Kennung des
 * Geraets, nie das Token.
 */
@RestController
@RequestMapping("/api/filiale/geraete")
public class FilialeGeraeteController {

    private final FilialGeraeteService service;

    public FilialeGeraeteController(FilialGeraeteService service) {
        this.service = service;
    }

    public record GeraetRequest(String label, String rolle) {}

    @Operation(summary = "Scanner-Geraete der eigenen Filiale")
    @GetMapping
    public List<FilialGeraeteService.Geraet> liste(Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = FilialeController.filiale(auth);
        return service.liste(p.shop(), p.zugang().getOrtId());
    }

    @Operation(summary = "Neues Geraet fuer die eigene Filiale",
            description = "rolle MITARBEITER (Standard), AUSHILFE oder KIOSK. Hoechstzahl je Filiale setzt der Admin.")
    @PostMapping
    public FilialGeraeteService.Geraet anlegen(@RequestBody GeraetRequest req, Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = FilialeController.filiale(auth);
        return service.anlegen(p.shop(), p.zugang().getOrtId(), req.label(), req.rolle());
    }

    @Operation(summary = "Geraet umbenennen oder Rolle aendern", description = "Felder, die fehlen, bleiben.")
    @PutMapping("/{kennung}")
    public FilialGeraeteService.Geraet aendern(@PathVariable String kennung, @RequestBody GeraetRequest req,
                                               Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = FilialeController.filiale(auth);
        return service.aendern(p.shop(), p.zugang().getOrtId(), kennung, req.label(), req.rolle());
    }

    @Operation(summary = "Geraet entfernen - sein Zugang gilt sofort nicht mehr")
    @DeleteMapping("/{kennung}")
    public ResponseEntity<Void> entfernen(@PathVariable String kennung, Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = FilialeController.filiale(auth);
        service.entfernen(p.shop(), p.zugang().getOrtId(), kennung);
        return ResponseEntity.noContent().build();
    }
}
