package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.KampagneRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.KampagnenService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Push-Kampagnen des Ladens. Eigener Controller, damit der ShopController
 * nicht weiter waechst; Zugang wie dort ueber das JWT (/api/shop/** verlangt
 * ROLE_SHOP in der SecurityConfig).
 */
@RestController
@RequestMapping("/api/shop/kampagnen")
public class KampagnenController {

    private final KampagnenService kampagnenService;
    private final KampagneRepository kampagneRepo;

    public KampagnenController(KampagnenService kampagnenService, KampagneRepository kampagneRepo) {
        this.kampagnenService = kampagnenService;
        this.kampagneRepo = kampagneRepo;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    // gruppe: Zielgruppe.name(), leer = ALLE.
    public record KampagneRequest(String text, String gruppe) {}

    public record KampagneItem(String id, String text, String gruppe, String status, int empfaenger,
                               int gesendet, String erstelltAm, String fertigAm) {
        static KampagneItem von(Kampagne k) {
            return new KampagneItem(k.getId(), k.getText(), k.getGruppe(), k.getStatus(),
                    k.getEmpfaenger(), k.getGesendet(), k.getErstelltAm().toString(),
                    k.getFertigAm() != null ? k.getFertigAm().toString() : null);
        }
    }

    public record GruppeItem(String gruppe, long empfaenger, long heuteSchon) {}

    public record KampagnenStand(int textMax, List<GruppeItem> gruppen, List<KampagneItem> items) {}

    @Operation(summary = "Stand der Push-Kampagnen",
            description = "Je Zielgruppe die Empfaengerzahl (ohne die, die heute schon eine "
                    + "bekommen haben), und die letzten 20 Kampagnen.")
    @GetMapping
    public KampagnenStand stand(Authentication auth) {
        Shop shop = currentShop(auth);
        List<GruppeItem> gruppen = kampagnenService.zaehle(shop).stream()
                .map(z -> new GruppeItem(z.gruppe().name(), z.empfaenger(), z.heuteSchon()))
                .toList();
        List<KampagneItem> items = kampagneRepo.findTop20ByShopOrderByErstelltAmDesc(shop)
                .stream().map(KampagneItem::von).toList();
        return new KampagnenStand(Kampagne.TEXT_MAX, gruppen, items);
    }

    @Operation(summary = "Push-Kampagne starten",
            description = "Text 1-160 Zeichen an eine Zielgruppe. Jede Karte bekommt hoechstens "
                    + "eine Kampagne am Tag. Der Versand laeuft im Hintergrund; der Stand "
                    + "steht danach im Verlauf.")
    @PostMapping
    public KampagneItem starte(@RequestBody KampagneRequest req, Authentication auth) {
        return KampagneItem.von(kampagnenService.starte(currentShop(auth), req.text(), req.gruppe()));
    }
}
