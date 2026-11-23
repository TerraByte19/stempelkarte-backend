package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine NFC-Karte am Tresen EINER Filiale (Tarif Kette). Jede hat ihren
 * eigenen Link und einen Namen ("Bahnhof, Theke 1"). So steht bei jedem
 * NFC-Stempel, in welcher Filiale und an welcher NFC-Karte er fiel - der
 * eine Link je Stempelkarte (Card.nfcToken) weiss das nicht.
 *
 * Nur IDs, keine Beziehungen: gelesen wird ausserhalb jeder Transaktion.
 * Das Token ist die Berechtigung selbst und gehoert in keine Verwaltungs-
 * Adresse - dort steht die id.
 */
@Entity
@Table(name = "nfc_tags", indexes = {
        @Index(name = "idx_nfctag_card", columnList = "card_id"),
        @Index(name = "idx_nfctag_shop", columnList = "shop_id")
})
public class NfcTag {

    public static final int LABEL_MAX = 40;

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "shop_id", nullable = false, length = 64)
    private String shopId;

    @Column(name = "card_id", nullable = false, length = 64)
    private String cardId;

    // Filiale (ShopOrt.id). Null = Hauptstandort.
    @Column(name = "ort_id", length = 64)
    private String ortId;

    @Column(nullable = false, length = LABEL_MAX)
    private String label;

    // Null = nicht gesperrt.
    @Column
    private Boolean gesperrt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NfcTag() {}

    public static NfcTag create(String shopId, String cardId, String ortId, String label) {
        NfcTag t = new NfcTag();
        t.id = "NFC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        t.token = UUID.randomUUID().toString().replace("-", "");
        t.shopId = shopId;
        t.cardId = cardId;
        t.ortId = (ortId == null || ortId.isBlank()) ? null : ortId;
        t.label = label;
        t.createdAt = Instant.now();
        return t;
    }

    public String getId() { return id; }
    public String getToken() { return token; }
    public String getShopId() { return shopId; }
    public String getCardId() { return cardId; }
    public String getOrtId() { return ortId; }
    public String getLabel() { return label; }
    public boolean isGesperrt() { return Boolean.TRUE.equals(gesperrt); }
    public void setGesperrt(boolean gesperrt) { this.gesperrt = gesperrt; }
    public Instant getCreatedAt() { return createdAt; }
}
