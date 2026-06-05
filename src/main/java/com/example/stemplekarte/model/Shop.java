package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shops")
public class Shop {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "hero_image_url")
    private String heroImageUrl;

    @Column(name = "color_background", length = 32)
    private String colorBackground;

    @Column(name = "color_foreground", length = 32)
    private String colorForeground;

    @Column(name = "color_label", length = 32)
    private String colorLabel;

    // --- Karten-Design ---
    @Column(name = "wallet_style", length = 16)
    private String walletStyle;        // "grid" oder "number"

    @Column(name = "stamp_icon_type", length = 16)
    private String stampIconType;      // "preset" oder "upload"

    @Column(name = "stamp_preset", length = 32)
    private String stampPreset;        // "coffee","star","heart","dot","square"

    @Column(name = "stamp_icon_url")
    private String stampIconUrl;       // bei eigenem Bild

    @Column(name = "stamp_color", length = 32)
    private String stampColor;

    @Column(name = "empty_stamp_style", length = 16)
    private String emptyStampStyle;    // "number" oder "faded"

    @Column(nullable = false)
    private boolean active;

    @Column(name = "max_tokens", nullable = false, columnDefinition = "integer default 3")
    private int maxTokens = 3;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Shop() {}

    public static Shop create(String email, String passwordHash, String name, int maxTokens) {
        Shop s = new Shop();
        s.id = "SHOP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        s.email = email;
        s.passwordHash = passwordHash;
        s.name = name;
        s.logoUrl = null;
        s.heroImageUrl = null;
        s.colorBackground = "#3C3489";
        s.colorForeground = "#FFFFFF";
        s.colorLabel = "#FAC875";
        s.walletStyle = "number";
        s.stampIconType = "preset";
        s.stampPreset = "coffee";
        s.stampIconUrl = null;
        s.stampColor = "#6F4E37";
        s.emptyStampStyle = "number";
        s.active = true;
        s.maxTokens = maxTokens;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        return s;
    }

    public void update(String name, String logoUrl, String colorBackground,
                       String colorForeground, String colorLabel) {
        if (name != null && !name.isBlank()) this.name = name;
        if (logoUrl != null) this.logoUrl = logoUrl;
        if (colorBackground != null) this.colorBackground = colorBackground;
        if (colorForeground != null) this.colorForeground = colorForeground;
        if (colorLabel != null) this.colorLabel = colorLabel;
        this.updatedAt = Instant.now();
    }

    public void updateDesign(String walletStyle, String stampIconType, String stampPreset,
                             String stampColor, String emptyStampStyle) {
        if (walletStyle != null) this.walletStyle = walletStyle;
        if (stampIconType != null) this.stampIconType = stampIconType;
        if (stampPreset != null) this.stampPreset = stampPreset;
        if (stampColor != null) this.stampColor = stampColor;
        if (emptyStampStyle != null) this.emptyStampStyle = emptyStampStyle;
        this.updatedAt = Instant.now();
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        this.updatedAt = Instant.now();
    }

    public void setHeroImageUrl(String heroImageUrl) {
        this.heroImageUrl = heroImageUrl;
        this.updatedAt = Instant.now();
    }

    public void setStampIconUrl(String stampIconUrl) {
        this.stampIconUrl = stampIconUrl;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public String getLogoUrl() { return logoUrl; }
    public String getHeroImageUrl() { return heroImageUrl; }
    public String getColorBackground() { return colorBackground; }
    public String getColorForeground() { return colorForeground; }
    public String getColorLabel() { return colorLabel; }
    public String getWalletStyle() { return walletStyle; }
    public String getStampIconType() { return stampIconType; }
    public String getStampPreset() { return stampPreset; }
    public String getStampIconUrl() { return stampIconUrl; }
    public String getStampColor() { return stampColor; }
    public String getEmptyStampStyle() { return emptyStampStyle; }
    public boolean isActive() { return active; }
    public int getMaxTokens() { return maxTokens; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}