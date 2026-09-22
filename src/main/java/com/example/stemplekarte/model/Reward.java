package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Eine Praemie im Katalog einer Punktekarte.
 *
 * Wird nie hart geloescht, nur auf active=false gesetzt: sonst verlieren
 * alte Buchungen ihren Bezug, und die Frage "was hat der Kunde damals
 * bekommen" ist nicht mehr zu beantworten.
 *
 * Der Preis liegt wie jeder Punktwert in Hundertsteln (25000 = 250 Punkte).
 */
@Entity
@Table(name = "rewards",
        indexes = @Index(name = "idx_reward_card", columnList = "card_id"))
public class Reward {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(name = "cost_points_x100", nullable = false)
    private long costPointsX100;

    @Column(name = "sort_order", nullable = false, columnDefinition = "integer default 0")
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Reward() {}

    public static Reward create(Card card, String name, long costPointsX100, int sortOrder) {
        Reward r = new Reward();
        r.id = "RW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        r.card = card;
        r.name = name;
        r.costPointsX100 = costPointsX100;
        r.sortOrder = sortOrder;
        r.active = true;
        r.createdAt = Instant.now();
        return r;
    }

    public void setActive(boolean active) { this.active = active; }
    public void setName(String name) { this.name = name; }
    public void setCostPointsX100(long costPointsX100) { this.costPointsX100 = costPointsX100; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getId() { return id; }
    public Card getCard() { return card; }
    public String getName() { return name; }
    public long getCostPointsX100() { return costPointsX100; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
