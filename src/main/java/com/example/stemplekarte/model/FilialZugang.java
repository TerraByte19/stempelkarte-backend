package com.example.stemplekarte.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Unterkonto einer Filiale (Tarif Kette): Benutzername + Passwort, vom
 * Hauptkonto vergeben. Darf nur /api/filiale/** - scannen, eigene Statistik,
 * eigene Geraete, Stempel korrigieren. Keine E-Mail: ein vergessenes Passwort
 * setzt das Hauptkonto neu.
 *
 * tokenVersion steht im Token (JwtService.generateFilialToken). Jeder
 * Passwortwechsel und jede Sperre zaehlt sie hoch, damit ausgestellte Tokens
 * sofort ungueltig sind.
 */
@Entity
@Table(name = "filial_zugaenge")
public class FilialZugang {

    public static final int NAME_MAX = 40;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{2,39}");
    public static final String NAME_REGEL =
            "Benutzername: 3 bis 40 Zeichen, nur Buchstaben, Ziffern und Bindestrich.";

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    // Null = Hauptstandort. Nur die ID, keine Beziehung (wie StaffToken.ortId).
    @Column(name = "ort_id", length = 64)
    private String ortId;

    @Column(nullable = false, unique = true, length = NAME_MAX)
    private String benutzername;

    @Column(name = "passwort_hash", nullable = false)
    private String passwortHash;

    @Column(nullable = false)
    private boolean gesperrt;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected FilialZugang() {}

    public static FilialZugang create(Shop shop, String benutzername, String passwortHash, String ortId) {
        FilialZugang z = new FilialZugang();
        z.id = "FZ-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        z.shop = shop;
        z.benutzername = benutzernameSauber(benutzername);
        z.passwortHash = passwortHash;
        z.ortId = (ortId == null || ortId.isBlank()) ? null : ortId;
        z.createdAt = Instant.now();
        return z;
    }

    /** Klein, getrimmt, ohne @ - so ist er nie mit einer Laden-E-Mail zu verwechseln. */
    public static String benutzernameSauber(String roh) {
        if (roh == null) throw new IllegalArgumentException(NAME_REGEL);
        String n = roh.trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(n).matches()) throw new IllegalArgumentException(NAME_REGEL);
        return n;
    }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getOrtId() { return ortId; }
    public String getBenutzername() { return benutzername; }
    public String getPasswortHash() { return passwortHash; }
    public boolean isGesperrt() { return gesperrt; }
    public int getTokenVersion() { return tokenVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }

    public void neuesPasswort(String hash) {
        this.passwortHash = hash;
        this.tokenVersion++;
    }

    public void sperre(boolean gesperrt) {
        this.gesperrt = gesperrt;
        this.tokenVersion++;
    }

    public void eingeloggt(Instant jetzt) { this.lastLoginAt = jetzt; }
}
