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
    public static final int ADRESSE_MAX = 200;

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

    // Nur fuer Ketten: eine Filiale kann eingetragen, aber noch nicht
    // eingeschaltet sein. Null = aktiv, damit bestehende Standorte bleiben,
    // wie sie sind.
    @Column
    private Boolean aktiv;

    // Kette: eine Filiale, die das Hauptkonto anlegt, muss der Admin erst
    // bestaetigen. Bis dahin zaehlt sie nirgends (isAktiv). Null = bestaetigt,
    // damit bestehende Standorte bleiben, wie sie sind.
    @Column
    private Boolean bestaetigt;

    // Adresse, wie sie beim Eintragen per Liste angegeben wurde. Nur zur
    // Anzeige; gerechnet wird mit den Koordinaten.
    @Column(length = ADRESSE_MAX)
    private String adresse;

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

    /** Eine Filiale, die noch auf die Bestaetigung wartet, ist nie aktiv. */
    public boolean isAktiv() { return (aktiv == null || aktiv) && isBestaetigt(); }
    public void setAktiv(boolean aktiv) { this.aktiv = aktiv; }

    public boolean isBestaetigt() { return bestaetigt == null || bestaetigt; }
    public void setBestaetigt(boolean bestaetigt) { this.bestaetigt = bestaetigt; }

    public String getAdresse() { return adresse; }
    public void setAdresse(String adresse) {
        String a = adresse == null ? "" : adresse.replaceAll("\\p{Cntrl}", " ").trim();
        this.adresse = a.isEmpty() ? null : (a.length() > ADRESSE_MAX ? a.substring(0, ADRESSE_MAX) : a);
    }

    /** Name und Lage pruefen die Aufrufer (OrteService). */
    public void umbenennen(String label) { this.label = label; }
    public void verlegen(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
