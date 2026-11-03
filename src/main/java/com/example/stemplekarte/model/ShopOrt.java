package com.example.stemplekarte.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Ein weiterer Standort eines Ladens: Filiale, Stand auf dem Wochenmarkt,
 * Food-Truck-Platz. Der Hauptstandort bleibt an Shop.latitude/longitude;
 * Apple nimmt bis zu zehn Orte pro Pass, also hoechstens neun hier. Wie
 * viele ein Laden haben darf, setzt der Admin (Shop.maxOrte).
 *
 * Dient der Naehe-Erinnerung, der Geo-Pruefung beim Scannen und - seit den
 * Filialen - der Zuordnung von Zugaengen (StaffToken.ortId) und Scans
 * (ScanLog.ortId, FilialenService). Eine Karte gilt an allen Standorten;
 * eigene Karten je Filiale gibt es bewusst nicht.
 */
@Entity
@Table(name = "shop_orte")
public class ShopOrt {

    public static final int LABEL_MAX = 40;

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false, length = LABEL_MAX)
    private String label;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ShopOrt() {}

    public static ShopOrt create(Shop shop, String label, double latitude, double longitude) {
        ShopOrt o = new ShopOrt();
        o.id = "ORT-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        o.shop = shop;
        o.label = label;
        o.latitude = latitude;
        o.longitude = longitude;
        o.createdAt = Instant.now();
        return o;
    }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getLabel() { return label; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public Instant getCreatedAt() { return createdAt; }
}
