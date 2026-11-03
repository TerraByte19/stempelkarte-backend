package com.example.stemplekarte.service;

import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Filialen = Standorte eines Ladens: der Hauptstandort (Shop.latitude/
 * longitude) plus die weiteren Orte (ShopOrt). Eine Karte gilt ueberall;
 * hier geht es darum, WO gestempelt wurde.
 *
 * Woher die Filiale eines Scans kommt: ist der Zugang (StaffToken) einer
 * Filiale zugeordnet, gilt die. Sonst der naechste Standort im Radius der
 * Geo-Pruefung, wenn das Geraet seinen Ort schickt. Sonst Hauptstandort.
 *
 * Wie viele Standorte ein Laden hat, setzt der Admin ueber Shop.maxOrte
 * (weitere Orte, 0-9); Filialen = 1 + maxOrte.
 */
@Service
public class FilialenService {

    /** Kennung des Hauptstandorts in Antworten und Anfragen (im Protokoll steht null). */
    public static final String HAUPT = "HAUPT";
    public static final String HAUPT_LABEL = "Hauptstandort";
    public static final String GELOESCHT_LABEL = "Gelöschter Standort";
    static final Duration FENSTER = Duration.ofDays(30);

    private final ShopOrtRepository ortRepo;
    private final ScanLogRepository scanLogRepo;
    private final StaffTokenRepository staffTokenRepo;

    public FilialenService(ShopOrtRepository ortRepo, ScanLogRepository scanLogRepo,
                           StaffTokenRepository staffTokenRepo) {
        this.ortRepo = ortRepo;
        this.scanLogRepo = scanLogRepo;
        this.staffTokenRepo = staffTokenRepo;
    }

    // ── Zuordnung beim Scan ─────────────────────────────────────────────

    /**
     * Filiale fuer das Protokoll: Zuordnung des Zugangs, sonst naechster
     * Standort im Radius, sonst null (Hauptstandort). Nur die vom Tarif
     * erlaubten Orte zaehlen - dieselbe Regel wie Pass und Geo-Pruefung.
     */
    public String ortFuer(StaffToken staff, Shop shop, Double lat, Double lon) {
        if (staff != null && staff.getOrtId() != null) return staff.getOrtId();
        if (shop == null || lat == null || lon == null) return null;
        int erlaubt = OrteService.erlaubt(shop);
        if (erlaubt <= 0) return null;
        long best = (shop.getLatitude() != null && shop.getLongitude() != null)
                ? GeoPruefung.distanzMeter(shop.getLatitude(), shop.getLongitude(), lat, lon)
                : Long.MAX_VALUE;
        String bestId = null;
        int n = 0;
        for (ShopOrt o : ortRepo.findByShopOrderByCreatedAtAsc(shop)) {
            if (n++ >= erlaubt) break;
            long d = GeoPruefung.distanzMeter(o.getLatitude(), o.getLongitude(), lat, lon);
            if (d < best) {
                best = d;
                bestId = o.getId();
            }
        }
        return best <= GeoPruefung.RADIUS_METER ? bestId : null;
    }

    // ── Uebersicht ──────────────────────────────────────────────────────

    /** Eine Filiale mit ihren Zahlen der letzten 30 Tage. */
    public record Filiale(String id, String label, boolean haupt, boolean geloescht,
                          long scans30d, long stempel30d, long kunden30d, String letzterScan,
                          long zugaenge) {}

    /** max = erlaubte Standorte insgesamt (1 + Admin-Wert), genutzt = Hauptstandort + angelegte Orte. */
    public record Stand(int max, int genutzt, List<Filiale> filialen) {}

    @Transactional(readOnly = true)
    public Stand stand(Shop shop, Instant jetzt) {
        List<ShopOrt> orte = ortRepo.findByShopOrderByCreatedAtAsc(shop);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(HAUPT, HAUPT_LABEL);
        for (ShopOrt o : orte) labels.put(o.getId(), o.getLabel());

        Map<String, long[]> zahlen = new HashMap<>();          // id -> [scans, stempel]
        Map<String, Set<String>> kunden = new HashMap<>();
        Map<String, Instant> zuletzt = new HashMap<>();
        for (ScanLog sl : scanLogRepo.findByShopIdAndScannedAtAfterOrderByScannedAtAsc(
                shop.getId(), jetzt.minus(FENSTER))) {
            String id = sl.getOrtId() != null ? sl.getOrtId() : HAUPT;
            long[] z = zahlen.computeIfAbsent(id, k -> new long[2]);
            z[0]++;
            z[1] += sl.getStampsAdded();
            if (sl.getCustomerId() != null) kunden.computeIfAbsent(id, k -> new HashSet<>()).add(sl.getCustomerId());
            Instant alt = zuletzt.get(id);
            if (alt == null || sl.getScannedAt().isAfter(alt)) zuletzt.put(id, sl.getScannedAt());
        }

        Map<String, Long> zugaenge = new HashMap<>();
        for (StaffToken t : staffTokenRepo.findByShop(shop)) {
            zugaenge.merge(t.getOrtId() != null ? t.getOrtId() : HAUPT, 1L, Long::sum);
        }

        // Geloeschte Orte mit Scans im Fenster bleiben als eigene Zeile sichtbar.
        for (String id : zahlen.keySet()) labels.putIfAbsent(id, GELOESCHT_LABEL);

        List<Filiale> liste = new ArrayList<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            String id = e.getKey();
            long[] z = zahlen.getOrDefault(id, new long[2]);
            boolean haupt = HAUPT.equals(id);
            liste.add(new Filiale(id, e.getValue(), haupt,
                    !haupt && GELOESCHT_LABEL.equals(e.getValue()),
                    z[0], z[1], kunden.getOrDefault(id, Set.of()).size(),
                    zuletzt.containsKey(id) ? zuletzt.get(id).toString() : null,
                    zugaenge.getOrDefault(id, 0L)));
        }
        return new Stand(1 + OrteService.erlaubt(shop), 1 + orte.size(), liste);
    }
}
