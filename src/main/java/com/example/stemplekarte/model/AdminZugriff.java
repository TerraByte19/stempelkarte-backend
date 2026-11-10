package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Protokoll: was der Betreiber aus dem Admin-Panel an einem Laden-Konto getan
 * hat - Dashboard geoeffnet (ZUGANG) oder Login-Adresse geaendert (EMAIL).
 *
 * <p>Der Betreiber sieht dabei Kundendaten des Ladens. Das Protokoll ist der
 * Nachweis, wann und wofuer. Bewusst ohne Fremdschluessel auf den Laden: der
 * Eintrag bleibt stehen, auch wenn der Laden spaeter geloescht wird. Es gibt
 * keinen Weg, Eintraege zu aendern oder zu loeschen.
 */
@Entity
@Table(name = "admin_zugriff")
public class AdminZugriff {

    public static final String ZUGANG = "ZUGANG";
    public static final String EMAIL = "EMAIL";

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "shop_id", length = 64, nullable = false)
    private String shopId;

    @Column(name = "shop_name", length = 255, nullable = false)
    private String shopName;

    @Column(length = 20, nullable = false)
    private String art;

    /** Bei EMAIL: "alt -> neu". Bei ZUGANG leer. */
    @Column(length = 600)
    private String detail;

    @Column(length = 64)
    private String ip;

    @Column(nullable = false)
    private Instant zeitpunkt;

    protected AdminZugriff() {}

    public static AdminZugriff create(String shopId, String shopName, String art, String detail, String ip) {
        AdminZugriff z = new AdminZugriff();
        z.id = "AZ-" + UUID.randomUUID();
        z.shopId = shopId;
        z.shopName = kuerze(shopName == null ? "" : shopName, 255);
        z.art = art;
        z.detail = detail == null ? null : kuerze(detail, 600);
        z.ip = ip == null ? null : kuerze(ip, 64);
        z.zeitpunkt = Instant.now();
        return z;
    }

    private static String kuerze(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public String getId() { return id; }
    public String getShopId() { return shopId; }
    public String getShopName() { return shopName; }
    public String getArt() { return art; }
    public String getDetail() { return detail; }
    public String getIp() { return ip; }
    public Instant getZeitpunkt() { return zeitpunkt; }
}
