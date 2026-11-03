package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Team-Verwaltung des Ladens: die Mitarbeiter-Zugaenge (StaffToken) mit
 * Name, Rolle, "zuletzt benutzt" und Stempeln der letzten 7 Tage.
 *
 * Der Stempel-Zaehler laeuft ueber den Namen (ScanLog.staffLabel), nicht
 * ueber das Token: das Token hat im Protokoll nichts verloren. Wird ein
 * Zugang umbenannt, beginnt sein Zaehler deshalb neu.
 */
@Service
public class TeamService {

    public static final int LABEL_MAX = 40;
    static final Duration ZAEHLER_FENSTER = Duration.ofDays(7);

    private final StaffTokenRepository staffTokenRepo;
    private final ScanLogRepository scanLogRepo;
    private final ShopService shopService;
    // Filialen (ShopOrt). Null in Tests, die den Dienst von Hand bauen.
    private final com.example.stemplekarte.repository.ShopOrtRepository ortRepo;

    public TeamService(StaffTokenRepository staffTokenRepo, ScanLogRepository scanLogRepo,
                       ShopService shopService) {
        this(staffTokenRepo, scanLogRepo, shopService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TeamService(StaffTokenRepository staffTokenRepo, ScanLogRepository scanLogRepo,
                       ShopService shopService, com.example.stemplekarte.repository.ShopOrtRepository ortRepo) {
        this.staffTokenRepo = staffTokenRepo;
        this.scanLogRepo = scanLogRepo;
        this.shopService = shopService;
        this.ortRepo = ortRepo;
    }

    /**
     * Das Token steht drin: der Laden braucht es fuer den Einrichtungs-QR.
     * ortId = FilialenService.HAUPT fuer den Hauptstandort, sonst ShopOrt.id.
     */
    public record Zugang(String token, String label, String rolle, String erstellt,
                         String zuletzt, long stempel7d, String ortId, String ortLabel) {}

    @Transactional(readOnly = true)
    public List<Zugang> liste(Shop shop, Instant jetzt) {
        return staffTokenRepo.findByShop(shop).stream()
                .sorted(Comparator.comparing(StaffToken::getCreatedAt))
                .map(t -> zugang(t, shop.getId(), jetzt))
                .toList();
    }

    public Zugang zugang(StaffToken t, String shopId, Instant jetzt) {
        long stempel = scanLogRepo.countByShopIdAndStaffLabelAndScannedAtAfter(
                shopId, t.getLabel(), jetzt.minus(ZAEHLER_FENSTER));
        String ortLabel = FilialenService.HAUPT_LABEL;
        if (t.getOrtId() != null) {
            ortLabel = ortRepo != null
                    ? ortRepo.findById(t.getOrtId()).map(com.example.stemplekarte.model.ShopOrt::getLabel)
                            .orElse(FilialenService.GELOESCHT_LABEL)
                    : FilialenService.GELOESCHT_LABEL;
        }
        return new Zugang(t.getToken(), t.getLabel(), t.getRolle(),
                t.getCreatedAt() != null ? t.getCreatedAt().toString() : null,
                t.getLastUsedAt() != null ? t.getLastUsedAt().toString() : null,
                stempel,
                t.getOrtId() != null ? t.getOrtId() : FilialenService.HAUPT,
                ortLabel);
    }

    /** Obergrenze (Shop.maxTokens) prueft der ShopService. */
    public StaffToken anlegen(Shop shop, String label, String rolle) {
        return anlegen(shop, label, rolle, null);
    }

    /** ortId null oder HAUPT = Hauptstandort, sonst ein Ort dieses Ladens. */
    @Transactional
    public StaffToken anlegen(Shop shop, String label, String rolle, String ortId) {
        StaffToken t = shopService.createStaffToken(shop.getId(), labelSauber(label), StaffToken.rolleSauber(rolle));
        String ort = ortSauber(shop, ortId);
        if (t != null && ort != null) {
            t.ort(ort);
            t = staffTokenRepo.save(t);
        }
        return t;
    }

    /** Null = Feld nicht anfassen. */
    @Transactional
    public StaffToken aendern(Shop shop, String token, String label, String rolle) {
        return aendern(shop, token, label, rolle, null);
    }

    /** ortId: null = nicht anfassen, HAUPT oder leer = Hauptstandort, sonst ein Ort dieses Ladens. */
    @Transactional
    public StaffToken aendern(Shop shop, String token, String label, String rolle, String ortId) {
        StaffToken t = eigener(shop, token);
        if (label != null) t.umbenennen(labelSauber(label));
        if (rolle != null) t.rolle(rolle);
        if (ortId != null) t.ort(ortSauber(shop, ortId));
        return staffTokenRepo.save(t);
    }

    /** Null fuer Hauptstandort; fremde oder unbekannte Orte = IllegalArgumentException (400). */
    private String ortSauber(Shop shop, String ortId) {
        if (ortId == null || ortId.isBlank() || FilialenService.HAUPT.equals(ortId.trim())) return null;
        String id = ortId.trim();
        if (ortRepo == null || ortRepo.findByIdAndShop(id, shop).isEmpty()) {
            throw new IllegalArgumentException("Standort nicht gefunden");
        }
        return id;
    }

    /** Der letzte Zugang bleibt - sonst kann niemand mehr stempeln. */
    @Transactional
    public void entfernen(Shop shop, String token) {
        StaffToken t = eigener(shop, token);
        if (staffTokenRepo.findByShop(shop).size() <= 1) {
            throw new IllegalStateException("Der letzte Zugang bleibt, sonst kann niemand mehr stempeln.");
        }
        staffTokenRepo.delete(t);
    }

    private StaffToken eigener(Shop shop, String token) {
        return staffTokenRepo.findById(token == null ? "" : token)
                .filter(t -> t.getShop() != null && shop.getId().equals(t.getShop().getId()))
                .orElseThrow(() -> new NoSuchElementException("Zugang nicht gefunden"));
    }

    static String labelSauber(String label) {
        String l = label == null ? "" : label.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").trim();
        if (l.isEmpty()) throw new IllegalArgumentException("Name fehlt");
        if (l.length() > LABEL_MAX) throw new IllegalArgumentException("Name höchstens " + LABEL_MAX + " Zeichen");
        return l;
    }
}
