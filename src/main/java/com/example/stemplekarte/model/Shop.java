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

    @Column(name = "color_background", length = 32)
    private String colorBackground;

    @Column(name = "color_foreground", length = 32)
    private String colorForeground;

    @Column(name = "color_label", length = 32)
    private String colorLabel;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Shop() {}

    public static Shop create(String email, String passwordHash, String name) {
        Shop s = new Shop();
        s.id = "SHOP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        s.email = email;
        s.passwordHash = passwordHash;
        s.name = name;
        s.logoUrl = null;
        s.colorBackground = "#3C3489";
        s.colorForeground = "#FFFFFF";
        s.colorLabel = "#FAC875";
        s.active = true;
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

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public String getLogoUrl() { return logoUrl; }
    public String getColorBackground() { return colorBackground; }
    public String getColorForeground() { return colorForeground; }
    public String getColorLabel() { return colorLabel; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}