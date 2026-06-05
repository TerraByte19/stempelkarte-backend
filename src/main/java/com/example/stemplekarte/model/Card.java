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

    // ── Farben & Bilder (pro Karte) ───────────────────────────────────────
    @Column(name = "color_background", length = 32)
    private String colorBackground;

    @Column(name = "color_foreground", length = 32)
    private String colorForeground;

    @Column(name = "color_label", length = 32)
    private String colorLabel;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "hero_image_url")
    private String heroImageUrl;

    // ── Stempel-Design (pro Karte) ────────────────────────────────────────
    @Column(name = "wallet_style", length = 16)
    private String walletStyle;

    @Column(name = "stamp_icon_type", length = 16)
    private String stampIconType;

    @Column(name = "stamp_preset", length = 32)
    private String stampPreset;

    @Column(name = "stamp_icon_url")
    private String stampIconUrl;

    @Column(name = "stamp_color", length = 32)
    private String stampColor;

    @Column(name = "empty_stamp_style", length = 16)
    private String emptyStampStyle;

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
        // Farb-Defaults vom Shop übernehmen
        c.colorBackground = shop.getColorBackground() != null ? shop.getColorBackground() : "#3C3489";
        c.colorForeground = shop.getColorForeground() != null ? shop.getColorForeground() : "#FFFFFF";
        c.colorLabel = shop.getColorLabel() != null ? shop.getColorLabel() : "#FAC875";
        c.logoUrl = shop.getLogoUrl();
        c.heroImageUrl = shop.getHeroImageUrl();
        // Stempel-Defaults
        c.walletStyle = "number";
        c.stampIconType = "preset";
        c.stampPreset = "coffee";
        c.stampColor = "#6F4E37";
        c.emptyStampStyle = "number";
        c.stampIconUrl = null;
        return c;
    }

    public void setActive(boolean active) { this.active = active; }

    public void updateDesign(String walletStyle, String stampIconType, String stampPreset,
                             String stampColor, String emptyStampStyle) {
        if (walletStyle != null) this.walletStyle = walletStyle;
        if (stampIconType != null) this.stampIconType = stampIconType;
        if (stampPreset != null) this.stampPreset = stampPreset;
        if (stampColor != null) this.stampColor = stampColor;
        if (emptyStampStyle != null) this.emptyStampStyle = emptyStampStyle;
    }

    public void updateColors(String colorBackground, String colorForeground, String colorLabel) {
        if (colorBackground != null) this.colorBackground = colorBackground;
        if (colorForeground != null) this.colorForeground = colorForeground;
        if (colorLabel != null) this.colorLabel = colorLabel;
    }

    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public void setHeroImageUrl(String heroImageUrl) { this.heroImageUrl = heroImageUrl; }
    public void setStampIconUrl(String stampIconUrl) { this.stampIconUrl = stampIconUrl; }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getRewardThreshold() { return rewardThreshold; }
    public String getRewardText() { return rewardText; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public String getColorBackground() { return colorBackground != null ? colorBackground : "#3C3489"; }
    public String getColorForeground() { return colorForeground != null ? colorForeground : "#FFFFFF"; }
    public String getColorLabel() { return colorLabel != null ? colorLabel : "#FAC875"; }
    public String getLogoUrl() { return logoUrl; }
    public String getHeroImageUrl() { return heroImageUrl; }
    public String getWalletStyle() { return walletStyle != null ? walletStyle : "number"; }
    public String getStampIconType() { return stampIconType != null ? stampIconType : "preset"; }
    public String getStampPreset() { return stampPreset != null ? stampPreset : "coffee"; }
    public String getStampIconUrl() { return stampIconUrl; }
    public String getStampColor() { return stampColor != null ? stampColor : "#6F4E37"; }
    public String getEmptyStampStyle() { return emptyStampStyle != null ? emptyStampStyle : "number"; }
}