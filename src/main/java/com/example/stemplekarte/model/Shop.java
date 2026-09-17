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

    // Unbeschnittene Ausgangsbilder - fuer spaeteres erneutes Zuschneiden.
    @Column(name = "logo_original_url")
    private String logoOriginalUrl;

    @Column(name = "hero_original_url")
    private String heroOriginalUrl;

    @Column(name = "stamp_icon_original_url")
    private String stampIconOriginalUrl;

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

    // Sprache des Ladens: "de" oder "ar". null/leer = "de" (alle Bestandslaeden).
    // Steuert Kundenkarte, Anmeldeseite, Bestaetigungsseiten und E-Mails.
    // Nullable -> ddl-auto:update braucht keinen DB-Reset.
    @Column(name = "language", length = 5)
    private String language;

    // ── Sperrbildschirm-Erinnerung ────────────────────────────────────────
    // Ist die Karte voll, soll sie sich in Ladennaehe von selbst auf dem
    // Sperrbildschirm melden. Technisch: "locations" im Apple-Pass. Das Handy
    // erledigt den Rest, wir schicken keine Benachrichtigung.
    //
    // Pro Laden abschaltbar, weil es ohne Koordinaten nicht geht und nicht
    // jeder Laden das will. Alle drei Felder nullable -> ddl-auto:update
    // braucht keinen DB-Reset, Bestandslaeden bleiben unberuehrt (= aus).
    @Column(name = "lock_screen_enabled")
    private Boolean lockScreenEnabled;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    // Eigener Text fuer die Sperrbildschirm-Erinnerung. Zwei Faelle, weil die
    // Karte in beiden Zustaenden in Ladennaehe auftaucht: volle Karte und noch
    // am Sammeln. Leer/null = Standardtext (siehe ApplePassService).
    // Platzhalter: {belohnung} = Belohnungstext, {stempel} = fehlende Stempel.
    @Column(name = "lock_screen_text_full", length = 120)
    private String lockScreenTextFull;

    @Column(name = "lock_screen_text_progress", length = 120)
    private String lockScreenTextProgress;

    // Sonntag aus der Statistik ausrechnen (Ø/Tag, staerkster/ruhigster Tag) -
    // viele Laeden haben sonntags zu, dann ist Sonntag trivial immer "ruhigster
    // Tag". Pro Laden vom Laden selbst umschaltbar; das Admin-Panel zeigt nur
    // den Wert, den der Laden gesetzt hat (kein eigener Schalter dort).
    @Column(name = "exclude_sunday_from_stats")
    private Boolean excludeSundayFromStats;

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

    public void setLogoOriginalUrl(String v) { this.logoOriginalUrl = v; this.updatedAt = Instant.now(); }
    public void setHeroOriginalUrl(String v) { this.heroOriginalUrl = v; this.updatedAt = Instant.now(); }
    public void setStampIconOriginalUrl(String v) { this.stampIconOriginalUrl = v; this.updatedAt = Instant.now(); }
    public String getLogoOriginalUrl() { return logoOriginalUrl; }
    public String getHeroOriginalUrl() { return heroOriginalUrl; }
    public String getStampIconOriginalUrl() { return stampIconOriginalUrl; }

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

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    /** null/leer (Bestandslaeden) gilt als Deutsch. */
    public String getLanguageOrDefault() {
        return (language == null || language.isBlank()) ? "de" : language;
    }

    // ── Sperrbildschirm-Erinnerung ────────────────────────────────────────

    public Boolean getLockScreenEnabled() { return lockScreenEnabled; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }

    /**
     * An ist die Erinnerung nur, wenn der Laden sie eingeschaltet hat UND
     * Koordinaten hinterlegt sind. Ohne Koordinaten gibt es nichts, worauf
     * sich das Handy beziehen koennte - dann ist der Schalter wirkungslos.
     */
    public boolean isLockScreenActive() {
        return Boolean.TRUE.equals(lockScreenEnabled)
                && latitude != null && longitude != null;
    }

    /**
     * Koordinaten setzen und Schalter umlegen. Schaltet der Laden ab, bleiben
     * die Koordinaten stehen - beim Wiedereinschalten muss er sie nicht neu
     * erfassen.
     */
    public void updateLockScreen(Boolean enabled, Double latitude, Double longitude) {
        if (enabled != null) this.lockScreenEnabled = enabled;
        if (latitude != null) this.latitude = latitude;
        if (longitude != null) this.longitude = longitude;
        this.updatedAt = Instant.now();
    }

    public String getLockScreenTextFull() { return lockScreenTextFull; }
    public String getLockScreenTextProgress() { return lockScreenTextProgress; }

    /** Leerer Text = zurueck auf den Standardtext. */
    public void updateLockScreenTexts(String full, String progress) {
        this.lockScreenTextFull = (full == null || full.isBlank()) ? null : full.trim();
        this.lockScreenTextProgress = (progress == null || progress.isBlank()) ? null : progress.trim();
        this.updatedAt = Instant.now();
    }

    // ── Statistik-Einstellungen ─────────────────────────────────────────

    public Boolean getExcludeSundayFromStats() { return excludeSundayFromStats; }

    public void setExcludeSundayFromStats(boolean enabled) {
        this.excludeSundayFromStats = enabled;
        this.updatedAt = Instant.now();
    }
}