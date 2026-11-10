package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.AdminZugangService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin: Dashboard eines Ladens oeffnen, Login-Adresse aendern, Protokoll.
 *
 * <p>Liegt unter /api/admin und ist damit ueber SecurityConfig nur mit
 * Admin-Token erreichbar (hasRole("ADMIN")). Eigener Controller statt
 * AdminController, damit dessen Konstruktor nicht weiter waechst.
 */
// KEIN @CrossOrigin hier — CORS läuft global über SecurityConfig
@Tag(name = "Admin", description = "Admin-Endpoints")
@RestController
@RequestMapping("/api/admin")
public class AdminZugangController {

    private final AdminZugangService dienst;

    public AdminZugangController(AdminZugangService dienst) {
        this.dienst = dienst;
    }

    @PostMapping("/shops/{shopId}/zugang")
    public Map<String, Object> zugang(@PathVariable String shopId,
                                      @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        AdminZugangService.Zugang z = dienst.oeffnen(shopId, ip(forwardedFor));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", z.token());
        m.put("shopId", z.shopId());
        m.put("name", z.name());
        m.put("bis", z.bis().toString());
        return m;
    }

    public record EmailRequest(String email) {}

    @PostMapping("/shops/{shopId}/email")
    public Map<String, Object> email(@PathVariable String shopId,
                                     @RequestBody EmailRequest req,
                                     @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        try {
            Shop shop = dienst.emailAendern(shopId, req == null ? null : req.email(), ip(forwardedFor));
            return Map.of("id", shop.getId(), "email", shop.getEmail());
        } catch (DataIntegrityViolationException e) {
            // Zwei Aenderungen auf dieselbe Adresse im selben Moment: die
            // Datenbank (unique) hat das letzte Wort.
            throw new IllegalArgumentException("Diese E-Mail gehört schon zu einem anderen Laden.");
        }
    }

    @GetMapping("/zugriffe")
    public List<Map<String, Object>> zugriffe() {
        return dienst.letzte().stream().map(z -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", z.getId());
            m.put("shopId", z.getShopId());
            m.put("shopName", z.getShopName());
            m.put("art", z.getArt());
            m.put("detail", z.getDetail());
            m.put("zeitpunkt", z.getZeitpunkt().toString());
            return m;
        }).toList();
    }

    /** Hinter Render steht die echte Adresse vorn in X-Forwarded-For. */
    private static String ip(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) return null;
        return forwardedFor.split(",")[0].trim();
    }
}
