package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Ein automatischer Nachrichten-Ausloeser eines Ladens (Willkommen, zweiter
 * Besuch, fast voll, Praemie bereit, VIP): ein Schalter und ein eigener
 * Text. Kein Text = Standardtext des Typs (service.AutoNachrichtTyp).
 * Eine Zeile je Laden und Typ; fehlt sie, ist der Automat aus.
 */
@Entity
@Table(name = "auto_nachrichten", uniqueConstraints = @UniqueConstraint(columnNames = {"shop_id", "typ"}))
public class AutoNachricht {

    public static final int TEXT_MAX = Kampagne.TEXT_MAX;

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false, length = 30)
    private String typ;

    @Column(nullable = false)
    private boolean aktiv;

    @Column(length = TEXT_MAX)
    private String text;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AutoNachricht() {}

    public static AutoNachricht neu(Shop shop, String typ, Instant jetzt) {
        AutoNachricht a = new AutoNachricht();
        a.id = "AN-" + UUID.randomUUID();
        a.shop = shop;
        a.typ = typ;
        a.aktiv = false;
        a.updatedAt = jetzt;
        return a;
    }

    /** text leer = Standardtext. Laenge prueft der Aufrufer. */
    public void einstellen(boolean aktiv, String text, Instant jetzt) {
        this.aktiv = aktiv;
        this.text = (text == null || text.isBlank()) ? null : text.trim();
        this.updatedAt = jetzt;
    }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getTyp() { return typ; }
    public boolean isAktiv() { return aktiv; }
    /** Eigener Text oder null (Standard). */
    public String getText() { return text; }
    public Instant getUpdatedAt() { return updatedAt; }
}
