package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.FilialStatistikService;
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
    private final FilialStatistikService statistik;

    public FilialeController(FilialZugangService filialZugangService, FilialStatistikService statistik) {
        this.filialZugangService = filialZugangService;
        this.statistik = statistik;
    }

    @Operation(summary = "Statistik der eigenen Filiale im gewaehlten Zeitraum",
            description = "von/bis als JJJJ-MM-TT, ohne Angabe die letzten 30 Tage. Nur die eigene Filiale, "
                    + "keine Kundendaten.")
    @GetMapping("/statistik")
    public FilialStatistikService.Zeile statistik(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String von,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String bis,
            Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = filiale(auth);
        return statistik.eine(p.shop(), p.zugang().getOrtId(), filialZugangService.ortLabel(p.zugang()),
                FilialStatistikService.zeitraum(von, bis, FilialStatistikService.heute()));
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
