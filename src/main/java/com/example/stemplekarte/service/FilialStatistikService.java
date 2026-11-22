package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Statistik je Filiale fuer einen frei gewaehlten Zeitraum: Stempel, neue
 * Kunden, eingeloeste Praemien, aktive Kunden. Gezaehlt wird in der
 * Datenbank (GROUP BY ort_id), nicht im Speicher - eine Kette hat schnell
 * zehntausende Scans.
 *
 * Das Hauptkonto sieht alle Filialen plus Summe, ein Unterkonto nur seine
 * eigene Zeile. Kundennamen kommen hier nirgends vor.
 */
@Service
public class FilialStatistikService {

    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** Ohne Angabe: die letzten 30 Tage einschliesslich heute. */
    static final int STANDARD_TAGE = 30;
    /** Laenger als gut ein Jahr auf einmal gibt es nicht. */
    static final int MAX_TAGE = 400;

    private final ScanLogRepository scanLogRepo;
    private final ShopOrtRepository ortRepo;

    public FilialStatistikService(ScanLogRepository scanLogRepo, ShopOrtRepository ortRepo) {
        this.scanLogRepo = scanLogRepo;
        this.ortRepo = ortRepo;
    }

    /** Beide Tage zaehlen mit. */
    public record Zeitraum(LocalDate von, LocalDate bis) {
        Instant start() { return von.atStartOfDay(ZONE).toInstant(); }
        Instant ende() { return bis.plusDays(1).atStartOfDay(ZONE).toInstant(); }
    }

    /**
     * von/bis als JJJJ-MM-TT, beide optional. Fehlt bis: heute. Fehlt von:
     * 30 Tage bis einschliesslich bis.
     */
    public static Zeitraum zeitraum(String von, String bis, LocalDate heute) {
        try {
            LocalDate b = (bis == null || bis.isBlank()) ? heute : LocalDate.parse(bis.trim());
            LocalDate v = (von == null || von.isBlank()) ? b.minusDays(STANDARD_TAGE - 1L) : LocalDate.parse(von.trim());
            if (v.isAfter(b)) throw new IllegalArgumentException("Der Zeitraum endet vor seinem Anfang.");
            if (v.plusDays(MAX_TAGE).isBefore(b)) {
                throw new IllegalArgumentException("Bitte höchstens " + MAX_TAGE + " Tage auf einmal wählen.");
            }
            return new Zeitraum(v, b);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Datum bitte als JJJJ-MM-TT angeben.");
        }
    }

    public static LocalDate heute() {
        return LocalDate.now(ZONE);
    }

    /**
     * Eine Filiale im Zeitraum. id HAUPT = Hauptstandort. aktiveKunden =
     * unterschiedliche Kunden mit mindestens einem Scan.
     */
    public record Zeile(String id, String label, boolean haupt, boolean aktiv, boolean geloescht,
                        long scans, long stempel, long neueKunden, long praemien, long aktiveKunden) {}

    public record Tabelle(String von, String bis, List<Zeile> filialen, Zeile gesamt) {}

    private record Zahlen(long scans, long stempel, long praemien, long kunden) {
        static final Zahlen LEER = new Zahlen(0, 0, 0, 0);
    }

    private static long zahl(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static String schluessel(Object ortId) {
        return ortId == null ? FilialenService.HAUPT : ortId.toString();
    }

    /** Alle Filialen plus Summe - fuer das Hauptkonto. */
    @Transactional(readOnly = true)
    public Tabelle tabelle(Shop shop, Zeitraum z) {
        Map<String, Zahlen> zahlen = new HashMap<>();
        for (Object[] r : scanLogRepo.zahlenJeFiliale(shop.getId(), z.start(), z.ende())) {
            zahlen.put(schluessel(r[0]), new Zahlen(zahl(r[1]), zahl(r[2]), zahl(r[3]), zahl(r[4])));
        }
        Map<String, Long> neu = new HashMap<>();
        for (Object[] r : scanLogRepo.neueKundenJeFiliale(shop.getId(), z.start(), z.ende())) {
            neu.put(schluessel(r[0]), zahl(r[1]));
        }

        // Reihenfolge: Hauptstandort, Filialen in Anlagereihenfolge, zuletzt
        // geloeschte Standorte, die im Zeitraum noch Scans haben.
        Map<String, Zeile> zeilen = new LinkedHashMap<>();
        boolean hauptZaehlt = !OrteService.kette(shop) || shop.isHauptIstFiliale();
        if (hauptZaehlt || zahlen.containsKey(FilialenService.HAUPT)) {
            zeilen.put(FilialenService.HAUPT, zeile(FilialenService.HAUPT, FilialenService.HAUPT_LABEL,
                    true, hauptZaehlt, false, zahlen, neu));
        }
        for (ShopOrt o : ortRepo.findByShopOrderByCreatedAtAsc(shop)) {
            // Ausgeschaltete Filiale ohne Scans im Zeitraum: keine Zeile.
            if (!o.isAktiv() && OrteService.kette(shop) && !zahlen.containsKey(o.getId())) continue;
            zeilen.put(o.getId(), zeile(o.getId(), o.getLabel(), false,
                    !OrteService.kette(shop) || o.isAktiv(), false, zahlen, neu));
        }
        for (String id : zahlen.keySet()) {
            if (!zeilen.containsKey(id)) {
                zeilen.put(id, zeile(id, FilialenService.GELOESCHT_LABEL, false, false, true, zahlen, neu));
            }
        }

        long scans = 0, stempel = 0, neue = 0, praemien = 0;
        for (Zeile f : zeilen.values()) {
            scans += f.scans();
            stempel += f.stempel();
            neue += f.neueKunden();
            praemien += f.praemien();
        }
        // Aktive Kunden NICHT addieren: wer in zwei Filialen war, ist ein Kunde.
        long kunden = scanLogRepo.kundenImZeitraum(shop.getId(), z.start(), z.ende());
        Zeile gesamt = new Zeile("GESAMT", "Gesamt", false, true, false, scans, stempel, neue, praemien, kunden);
        return new Tabelle(z.von().toString(), z.bis().toString(), new ArrayList<>(zeilen.values()), gesamt);
    }

    /** Die Zeile EINER Filiale - fuer das Unterkonto. ortId null = Hauptstandort. */
    @Transactional(readOnly = true)
    public Zeile eine(Shop shop, String ortId, String label, Zeitraum z) {
        String id = schluessel(ortId);
        Map<String, Zahlen> zahlen = new HashMap<>();
        for (Object[] r : scanLogRepo.zahlenJeFiliale(shop.getId(), z.start(), z.ende())) {
            if (id.equals(schluessel(r[0]))) {
                zahlen.put(id, new Zahlen(zahl(r[1]), zahl(r[2]), zahl(r[3]), zahl(r[4])));
            }
        }
        Map<String, Long> neu = new HashMap<>();
        for (Object[] r : scanLogRepo.neueKundenJeFiliale(shop.getId(), z.start(), z.ende())) {
            if (id.equals(schluessel(r[0]))) neu.put(id, zahl(r[1]));
        }
        return zeile(id, label, ortId == null, true, false, zahlen, neu);
    }

    private static Zeile zeile(String id, String label, boolean haupt, boolean aktiv, boolean geloescht,
                               Map<String, Zahlen> zahlen, Map<String, Long> neu) {
        Zahlen n = zahlen.getOrDefault(id, Zahlen.LEER);
        return new Zeile(id, label, haupt, aktiv, geloescht,
                n.scans(), n.stempel(), neu.getOrDefault(id, 0L), n.praemien(), n.kunden());
    }
}
