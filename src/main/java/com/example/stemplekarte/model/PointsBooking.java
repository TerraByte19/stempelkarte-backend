package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Eine Punktebewegung. Aus dieser Tabelle fallen Handkorrektur, Ruecknahme,
 * Statistik und die Antwort auf "was ist an dem Dienstag passiert".
 *
 * Bewusst NICHT der ScanLog: der ist wegwerfbar gebaut, sein Schreibfehler
 * wird geschluckt, damit ein Scan nie an der Statistik scheitert. Eine
 * Buchung darf das nicht - scheitert sie, muss der Punktestand mit
 * zurueckrollen.
 *
 * Nur IDs, keine harten Beziehungen - wie ScanLog, damit das Loeschen einer
 * Karte nicht an Buchungen scheitert.
 *
 * Gespeichert wird staffLabel ("Kasse 1"), NICHT das Staff-Token: dessen
 * Wert ist zugleich Primaerschluessel und Zugangsberechtigung im
 * X-Staff-Token-Header. Da der Scanner Buchungen anzeigt, waere die
 * Berechtigung eines Geraets sonst ueber die Buchungsliste ablesbar.
 */
@Entity
@Table(name = "points_bookings",
        indexes = {
                @Index(name = "idx_booking_cc_time", columnList = "customer_card_id, created_at"),
                @Index(name = "idx_booking_shop_time", columnList = "shop_id, created_at")
        })
public class PointsBooking {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "customer_card_id", nullable = false, length = 64)
    private String customerCardId;

    @Column(name = "card_id", nullable = false, length = 64)
    private String cardId;

    @Column(name = "shop_id", nullable = false, length = 64)
    private String shopId;

    @Column(name = "customer_id", length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingKind kind;

    /** Mit Vorzeichen. Was tatsaechlich gebucht wurde, nicht was gemeint war. */
    @Column(name = "delta_points_x100", nullable = false)
    private long deltaPointsX100;

    @Column(name = "amount_cents")
    private Long amountCents;

    /** Abschrift des Kurses, der galt. */
    @Column(name = "points_per_euro_x100")
    private Integer pointsPerEuroX100;

    @Column(name = "reward_id", length = 64)
    private String rewardId;

    /** Abschrift: benennt der Laden die Praemie um, bleibt die Historie wahr. */
    @Column(name = "reward_name", length = 40)
    private String rewardName;

    @Column(name = "reward_cost_points_x100")
    private Long rewardCostPointsX100;

    @Column(name = "staff_label")
    private String staffLabel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reversal_of_id", length = 64)
    private String reversalOfId;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    protected PointsBooking() {}

    private static PointsBooking basis(CustomerCard cc, String shopId,
                                       BookingKind kind, long deltaPointsX100,
                                       String staffLabel) {
        PointsBooking b = new PointsBooking();
        b.id = "PB-" + UUID.randomUUID();
        b.customerCardId = cc.getId();
        b.cardId = cc.getCard().getId();
        b.shopId = shopId;
        b.customerId = cc.getCustomer().getId();
        b.kind = kind;
        b.deltaPointsX100 = deltaPointsX100;
        b.staffLabel = staffLabel;
        b.createdAt = Instant.now();
        return b;
    }

    public static PointsBooking earn(CustomerCard cc, String shopId, long deltaPointsX100,
                                     long amountCents, int pointsPerEuroX100,
                                     String staffLabel) {
        PointsBooking b = basis(cc, shopId, BookingKind.EARN, deltaPointsX100, staffLabel);
        b.amountCents = amountCents;
        b.pointsPerEuroX100 = pointsPerEuroX100;
        return b;
    }

    public static PointsBooking redeem(CustomerCard cc, String shopId, Reward reward,
                                       String staffLabel) {
        PointsBooking b = basis(cc, shopId, BookingKind.REDEEM,
                -reward.getCostPointsX100(), staffLabel);
        b.rewardId = reward.getId();
        b.rewardName = reward.getName();
        b.rewardCostPointsX100 = reward.getCostPointsX100();
        return b;
    }

    public static PointsBooking correction(CustomerCard cc, String shopId,
                                           long deltaPointsX100, Long amountCents,
                                           Integer pointsPerEuroX100, String staffLabel) {
        PointsBooking b = basis(cc, shopId, BookingKind.CORRECTION, deltaPointsX100, staffLabel);
        b.amountCents = amountCents;
        b.pointsPerEuroX100 = pointsPerEuroX100;
        return b;
    }

    public static PointsBooking reversal(CustomerCard cc, String shopId,
                                         PointsBooking original, long deltaPointsX100,
                                         String staffLabel) {
        PointsBooking b = basis(cc, shopId, BookingKind.REVERSAL, deltaPointsX100, staffLabel);
        b.reversalOfId = original.getId();
        // Die Praemien-Abschrift wandert mit, damit in der Liste steht,
        // WAS zurueckgenommen wurde.
        b.rewardId = original.getRewardId();
        b.rewardName = original.getRewardName();
        b.rewardCostPointsX100 = original.getRewardCostPointsX100();
        b.amountCents = original.getAmountCents();
        return b;
    }

    public void markiereAlsZurueckgenommen() {
        this.reversedAt = Instant.now();
    }

    public boolean istZurueckgenommen() { return reversedAt != null; }

    public String getId() { return id; }
    public String getCustomerCardId() { return customerCardId; }
    public String getCardId() { return cardId; }
    public String getShopId() { return shopId; }
    public String getCustomerId() { return customerId; }
    public BookingKind getKind() { return kind; }
    public long getDeltaPointsX100() { return deltaPointsX100; }
    public Long getAmountCents() { return amountCents; }
    public Integer getPointsPerEuroX100() { return pointsPerEuroX100; }
    public String getRewardId() { return rewardId; }
    public String getRewardName() { return rewardName; }
    public Long getRewardCostPointsX100() { return rewardCostPointsX100; }
    public String getStaffLabel() { return staffLabel; }
    public Instant getCreatedAt() { return createdAt; }
    public String getReversalOfId() { return reversalOfId; }
    public Instant getReversedAt() { return reversedAt; }
}
