package com.example.stemplekarte.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Protokoll der Stempel-Korrekturen durch Filialen (Tarif Kette): welche
 * Filiale, welcher Login, von wie vielen auf wie viele Stempel.
 *
 * Bewusst eine eigene Tabelle und KEIN Eintrag im ScanLog: dort haengen
 * Sperrzeiten (Tablet, NFC), Besuchszaehler und "aktive Kunden" an jeder
 * Zeile. Eine Korrektur ist kein Besuch.
 *
 * Nur IDs, kein Kundenname (wie ScanLog).
 */
@Entity
@Table(name = "korrektur_logs",
        indexes = @Index(name = "idx_korrektur_shop_zeit", columnList = "shop_id, erstellt_am"))
public class KorrekturLog {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "shop_id", nullable = false, length = 64)
    private String shopId;

    // Null = Hauptstandort.
    @Column(name = "ort_id", length = 64)
    private String ortId;

    @Column(name = "customer_card_id", nullable = false, length = 64)
    private String customerCardId;

    // Benutzername des Unterkontos.
    @Column(nullable = false, length = FilialZugang.NAME_MAX)
    private String wer;

    @Column(nullable = false)
    private int vorher;

    @Column(nullable = false)
    private int nachher;

    @Column(name = "erstellt_am", nullable = false)
    private Instant erstelltAm;

    protected KorrekturLog() {}

    public static KorrekturLog create(String shopId, String ortId, String customerCardId,
                                      String wer, int vorher, int nachher) {
        KorrekturLog k = new KorrekturLog();
        k.id = "KL-" + UUID.randomUUID();
        k.shopId = shopId;
        k.ortId = (ortId == null || ortId.isBlank()) ? null : ortId;
        k.customerCardId = customerCardId;
        k.wer = wer;
        k.vorher = vorher;
        k.nachher = nachher;
        k.erstelltAm = Instant.now();
        return k;
    }

    public String getId() { return id; }
    public String getShopId() { return shopId; }
    public String getOrtId() { return ortId; }
    public String getCustomerCardId() { return customerCardId; }
    public String getWer() { return wer; }
    public int getVorher() { return vorher; }
    public int getNachher() { return nachher; }
    public Instant getErstelltAm() { return erstelltAm; }
}
