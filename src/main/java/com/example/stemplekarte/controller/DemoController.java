package com.example.stemplekarte.controller;

import com.example.stemplekarte.service.DemoService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Demo-Karten fuer die Website. Oeffentlich (/api/public/** ist frei);
 * der Dienst laesst nur Karten von Demo-Laeden zu und drosselt.
 */
@RestController
@RequestMapping("/api/public/demo")
public class DemoController {

    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    @Operation(summary = "Demo-Karten", description = "Karten aller Demo-Laeden mit Link zur Anmeldeseite.")
    @GetMapping
    public List<DemoService.DemoKarte> karten() {
        return demoService.karten();
    }

    public record SchrittRequest(String customerId, String cardId) {}

    @Operation(summary = "Demo-Schritt: was an der Kasse als Naechstes passieren wuerde",
            description = "Geschenk einloesen, Stempel, Einkauf buchen oder Paket aufladen - je nach Karte und Stand. "
                    + "Nur fuer Karten von Demo-Laeden, hoechstens alle 3 Sekunden und 30-mal am Tag je Karte.")
    @PostMapping("/stempel")
    public DemoService.Ergebnis stempel(@RequestBody SchrittRequest req) {
        return demoService.schritt(req.customerId(), req.cardId(), Instant.now());
    }
}
