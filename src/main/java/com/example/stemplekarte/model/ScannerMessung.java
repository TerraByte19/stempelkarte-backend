package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Eine Runde der stillen Scanner-Messung (Frontend lib/scannerWahl): beim
 * normalen Stempeln liest neben dem heutigen Scanner ("alt") ein
 * Herausforderer mit. Hier landet, wer wann getroffen hat - ohne
 * Kundendaten, nur Geraet und Zeiten. Der Admin sieht daraus je
 * Geraetetyp, welcher Scanner gewinnt.
 */
@Entity
@Table(name = "scanner_messungen",
        indexes = @Index(name = "idx_scanner_messung_zeit", columnList = "erstellt_am"))
public class ScannerMessung {

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "shop_id", length = 64, nullable = false)
    private String shopId;

    @Column(name = "staff_label", length = 100)
    private String staffLabel;

    /** ios, android, andere */
    @Column(length = 20)
    private String plattform;

    /** User-Agent, gekuerzt. Daraus leitet der Admin den Geraetetyp ab. */
    @Column(length = 200)
    private String geraet;

    /** neu, nimiq, handy */
    @Column(length = 10, nullable = false)
    private String gegner;

    /** ms bis der heutige Scanner traf */
    @Column(name = "alt_ms", nullable = false)
    private int altMs;

    /** ms bis der Herausforderer traf, null = nicht getroffen */
    @Column(name = "gegner_ms")
    private Integer gegnerMs;

    /** Welcher Leser auf dem Geraet gerade dauerhaft an ist (null = keiner). */
    @Column(length = 10)
    private String aktiv;

    @Column(name = "erstellt_am", nullable = false)
    private Instant erstelltAm;

    protected ScannerMessung() {}

    public static ScannerMessung create(String shopId, String staffLabel, String plattform, String geraet,
                                        String gegner, int altMs, Integer gegnerMs, String aktiv, Instant jetzt) {
        ScannerMessung m = new ScannerMessung();
        m.id = "SM-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
        m.shopId = shopId;
        m.staffLabel = kurz(staffLabel, 100);
        m.plattform = kurz(plattform, 20);
        m.geraet = kurz(geraet, 200);
        m.gegner = gegner;
        m.altMs = altMs;
        m.gegnerMs = gegnerMs;
        m.aktiv = kurz(aktiv, 10);
        m.erstelltAm = jetzt;
        return m;
    }

    private static String kurz(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * Geraetetyp aus dem User-Agent: "iPhone iOS 17", "iPad", "Android SM-G991B",
     * sonst die Plattform. Grob, aber gut genug fuer die Tabelle im Admin.
     */
    public String geraeteTyp() {
        String ua = geraet == null ? "" : geraet;
        java.util.regex.Matcher ios = java.util.regex.Pattern.compile("(iPhone|iPad).*?OS (\\d+)").matcher(ua);
        if (ios.find()) return ios.group(1) + " iOS " + ios.group(2);
        if (ua.contains("iPhone")) return "iPhone";
        if (ua.contains("iPad")) return "iPad";
        java.util.regex.Matcher android = java.util.regex.Pattern.compile("Android [\\d.]+; ([^;)]+?)(?: Build|\\))").matcher(ua);
        if (android.find()) return "Android " + android.group(1).trim();
        if (ua.contains("Android")) return "Android";
        return plattform != null ? plattform : "unbekannt";
    }

    public String getId() { return id; }
    public String getShopId() { return shopId; }
    public String getStaffLabel() { return staffLabel; }
    public String getPlattform() { return plattform; }
    public String getGeraet() { return geraet; }
    public String getGegner() { return gegner; }
    public int getAltMs() { return altMs; }
    public Integer getGegnerMs() { return gegnerMs; }
    public String getAktiv() { return aktiv; }
    public Instant getErstelltAm() { return erstelltAm; }
}
