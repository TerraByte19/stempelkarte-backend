package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Ein Scan, der nicht dort passiert ist, wo der Laden steht.
 *
 * <p>Bewusst eine eigene Tabelle und kein Feld im ScanLog: hier stehen nur
 * die Faelle, die jemand ansehen soll. Jeder normale Scan traegt dann auch
 * keinen Aufenthaltsort des Personals in der Datenbank mit sich herum.
 *
 * <p>Es wird nichts blockiert. Der Ort aus dem Browser ist zu unzuverlaessig,
 * um daran einen Stempel scheitern zu lassen - ein Tablet ohne GPS schaetzt
 * ueber das WLAN und liegt gern hundert Meter daneben. Der Vorfall ist ein
 * Hinweis, kein Urteil.
 */
@Entity
@Table(name = "geo_vorfall")
public class GeoVorfall {

    public enum Art {
        /** Ort bekannt, aber weiter weg als erlaubt. */
        WEIT_WEG,
        /** Geraet hat keinen Ort geliefert - Erlaubnis verweigert oder kein Empfang. */
        OHNE_ORT
    }

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "shop_id", length = 40, nullable = false)
    private String shopId;

    @Column(name = "card_id", length = 40)
    private String cardId;

    @Column(name = "customer_id", length = 60)
    private String customerId;

    /** Die Beschriftung des Personal-Tokens ("Kasse 1"), nie der Token selbst. */
    @Column(name = "staff_label", length = 60)
    private String staffLabel;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private Art art;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    /** Luftlinie zum hinterlegten Ort des Ladens. Null, wenn kein Ort kam. */
    @Column(name = "distanz_meter")
    private Long distanzMeter;

    @Column(name = "passiert_am", nullable = false)
    private Instant passiertAm;

    protected GeoVorfall() {}

    public static GeoVorfall create(String shopId, String cardId, String customerId,
                                    String staffLabel, Art art,
                                    Double latitude, Double longitude, Long distanzMeter) {
        GeoVorfall v = new GeoVorfall();
        v.id = "GEO-" + UUID.randomUUID();
        v.shopId = shopId;
        v.cardId = cardId;
        v.customerId = customerId;
        v.staffLabel = staffLabel;
        v.art = art;
        v.latitude = latitude;
        v.longitude = longitude;
        v.distanzMeter = distanzMeter;
        v.passiertAm = Instant.now();
        return v;
    }

    public String getId() { return id; }
    public String getShopId() { return shopId; }
    public String getCardId() { return cardId; }
    public String getCustomerId() { return customerId; }
    public String getStaffLabel() { return staffLabel; }
    public Art getArt() { return art; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Long getDistanzMeter() { return distanzMeter; }
    public Instant getPassiertAm() { return passiertAm; }
}
