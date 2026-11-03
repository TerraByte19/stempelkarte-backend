package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Ein API-Schluessel eines Ladens. Gespeichert wird nur der SHA-256-Hash -
 * der Schluessel selbst wird beim Anlegen einmal gezeigt und ist danach
 * weg. prefix (die ersten Zeichen) dient nur dem Wiedererkennen in der Liste.
 * Widerrufen = revokedAt gesetzt; die Zeile bleibt als Nachweis.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    public static final int LABEL_MAX = 40;

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false, length = LABEL_MAX)
    private String label;

    @Column(nullable = false, length = 12)
    private String prefix;

    @Column(nullable = false, unique = true, length = 64)
    private String hash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {}

    public static ApiKey neu(Shop shop, String label, String prefix, String hash, Instant jetzt) {
        ApiKey k = new ApiKey();
        k.id = "AK-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        k.shop = shop;
        k.label = label;
        k.prefix = prefix;
        k.hash = hash;
        k.createdAt = jetzt;
        return k;
    }

    public void benutzt(Instant jetzt) { this.lastUsedAt = jetzt; }
    public void widerrufen(Instant jetzt) { this.revokedAt = jetzt; }
    public boolean istAktiv() { return revokedAt == null; }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getLabel() { return label; }
    public String getPrefix() { return prefix; }
    public String getHash() { return hash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
