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

    // ── Design-Felder (pro Karte) ─────────────────────────────────────────
    @Column(name = "wallet_style", length = 16)
    private String walletStyle;       // "grid" oder "number"

    @Column(name = "stamp_icon_type", length = 16)
    private String stampIconType;     // "preset" oder "upload"

    @Column(name = "stamp_preset", length = 32)
    private String stampPreset;       // "coffee","star","heart","dot","square"

    @Column(name = "stamp_icon_url")
    private String stampIconUrl;      // bei eigenem Bild

    @Column(name = "stamp_color", length = 32)
    private String stampColor;

    @Column(name = "empty_stamp_style", length = 16)
    private String emptyStampStyle;   // "number" oder "faded"
    // ─────────────────────────────────────────────────────────────────────

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
        // Design-Defaults
        c.walletStyle = "number";
        c.stampIconType = "preset";
        c.stampPreset = "coffee";
        c.stampColor = "#6F4E37";
        c.emptyStampStyle = "number";
        c.stampIconUrl = null;
        return c;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void updateDesign(String walletStyle, String stampIconType, String stampPreset,
                             String stampColor, String emptyStampStyle) {
        if (walletStyle != null) this.walletStyle = walletStyle;
        if (stampIconType != null) this.stampIconType = stampIconType;
        if (stampPreset != null) this.stampPreset = stampPreset;
        if (stampColor != null) this.stampColor = stampColor;
        if (emptyStampStyle != null) this.emptyStampStyle = emptyStampStyle;
    }

    public void setStampIconUrl(String stampIconUrl) {
        this.stampIconUrl = stampIconUrl;
    }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getRewardThreshold() { return rewardThreshold; }
    public String getRewardText() { return rewardText; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public String getWalletStyle() { return walletStyle != null ? walletStyle : "number"; }
    public String getStampIconType() { return stampIconType != null ? stampIconType : "preset"; }
    public String getStampPreset() { return stampPreset != null ? stampPreset : "coffee"; }
    public String getStampIconUrl() { return stampIconUrl; }
    public String getStampColor() { return stampColor != null ? stampColor : "#6F4E37"; }
    public String getEmptyStampStyle() { return emptyStampStyle != null ? emptyStampStyle : "number"; }
}