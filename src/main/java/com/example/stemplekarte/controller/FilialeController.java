package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.FilialZugangService;
import com.example.stemplekarte.service.FilialenService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bereich der Unterkonten (Tarif Kette). Nur ROLE_FILIALE kommt hierher
 * (SecurityConfig). Alles hier ist auf die EIGENE Filiale begrenzt - die
 * Filiale kommt immer aus dem Login, nie aus der Anfrage.
 */
@RestController
@RequestMapping("/api/filiale")
public class FilialeController {

    private final FilialZugangService filialZugangService;

    public FilialeController(FilialZugangService filialZugangService) {
        this.filialZugangService = filialZugangService;
    }

    static JwtAuthFilter.FilialPrincipal filiale(Authentication auth) {
        return (JwtAuthFilter.FilialPrincipal) auth.getPrincipal();
    }

    @Operation(summary = "Wer bin ich: Kette, Benutzername, eigene Filiale")
    @GetMapping("/ich")
    public Map<String, Object> ich(Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = filiale(auth);
        FilialZugang z = p.zugang();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kette", p.shop().getName());
        m.put("benutzername", z.getBenutzername());
        m.put("ortId", z.getOrtId() != null ? z.getOrtId() : FilialenService.HAUPT);
        m.put("ortLabel", filialZugangService.ortLabel(z));
        return m;
    }
}
