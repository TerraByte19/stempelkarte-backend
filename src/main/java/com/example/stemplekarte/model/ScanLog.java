package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Protokolliert jeden einzelnen Scan mit Zeitstempel. Damit lassen sich
 * Verlaufs-Statistiken bauen (z.B. Stempel pro Tag/Woche), die aus dem
 * reinen Endstand der Karten nicht ableitbar sind.
 *
 * Diese Tabelle ist rein additiv — sie beeinflusst keine bestehenden
 * Tabellen und wird von ddl-auto=update automatisch angelegt.
 */
@Entity
@Table(name = "scan_logs",
        indexes = {
                @Index(name = "idx_scanlog_shop_time", columnList = "shop_id, scanned_at"),
                @Index(name = "idx_scanlog_card", columnList = "card_id"),
                // Letzter Scan einer Kundenkarte (Sperrzeit am Tablet, KioskService)
                @Index(name = "idx_scanlog_kunde", columnList = "shop_id, customer_id, card_id, scanned_at")
        })
public class ScanLog {

    @Id
    @Column(length = 64)
    private String id;

    // Nur IDs speichern (keine harten Beziehungen), damit das Löschen einer
    // Karte/eines Shops nicht an Log-Einträgen scheitert und das Logging
    // möglichst leichtgewichtig bleibt.
    @Column(name = "shop_id", nullable = false, length = 64)
    private String shopId;

    @Column(name = "card_id", nullable = false, length = 64)
    private String cardId;

    @Column(name = "customer_id", length = 64)
    private String customerId;

    // Wie viele Stempel dieser Scan vergeben hat (count)
    @Column(name = "stamps_added", nullable = false)
    private int stampsAdded;

    // Wie viele Belohnungen in diesem Scan verdient wurden
    @Column(name = "rewards_earned", nullable = false)
    private int rewardsEarned;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;

    // Name des Mitarbeiter-Zugangs, der gestempelt hat (StaffToken.label zum
    // Zeitpunkt des Scans). Null bei aelteren Eintraegen.
    @Column(name = "staff_label", length = 100)
    private String staffLabel;

    // Filiale (ShopOrt.id), in der gescannt wurde. Null = Hauptstandort oder
    // aelterer Eintrag. Nur die ID (siehe oben).
    @Column(name = "ort_id", length = 64)
    private String ortId;

    protected ScanLog() {}

    public static ScanLog create(String shopId, String cardId, String customerId,
                                 int stampsAdded, int rewardsEarned) {
        return create(shopId, cardId, customerId, stampsAdded, rewardsEarned, null);
    }

    public static ScanLog create(String shopId, String cardId, String customerId,
                                 int stampsAdded, int rewardsEarned, String staffLabel) {
        return create(shopId, cardId, customerId, stampsAdded, rewardsEarned, staffLabel, null);
    }

    public static ScanLog create(String shopId, String cardId, String customerId,
                                 int stampsAdded, int rewardsEarned, String staffLabel, String ortId) {
        ScanLog l = new ScanLog();
        l.ortId = (ortId == null || ortId.isBlank()) ? null : ortId;
        l.id = "SL-" + UUID.randomUUID();
        l.shopId = shopId;
        l.cardId = cardId;
        l.customerId = customerId;
        l.stampsAdded = stampsAdded;
        l.rewardsEarned = rewardsEarned;
        l.scannedAt = Instant.now();
        l.staffLabel = (staffLabel == null || staffLabel.isBlank()) ? null
                : (staffLabel.length() > 100 ? staffLabel.substring(0, 100) : staffLabel);
        return l;
    }

    public String getStaffLabel() { return staffLabel; }
    public String getOrtId() { return ortId; }

    public String getId() { return id; }
    public String getShopId() { return shopId; }
    public String getCardId() { return cardId; }
    public String getCustomerId() { return customerId; }
    public int getStampsAdded() { return stampsAdded; }
    public int getRewardsEarned() { return rewardsEarned; }
    public Instant getScannedAt() { return scannedAt; }
}