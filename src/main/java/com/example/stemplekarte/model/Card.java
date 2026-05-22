package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cards")
public class Card {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "reward_threshold", nullable = false)
    private int rewardThreshold;

    @Column(name = "reward_text", nullable = false)
    private String rewardText;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Card() {}

    public static Card create(Shop shop, String name, String description,
                              int rewardThreshold, String rewardText) {
        Card c = new Card();
        c.id = "CARD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        c.shop = shop;
        c.name = name;
        c.description = description;
        c.rewardThreshold = rewardThreshold;
        c.rewardText = rewardText;
        c.active = true;
        c.createdAt = Instant.now();
        return c;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getRewardThreshold() { return rewardThreshold; }
    public String getRewardText() { return rewardText; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}