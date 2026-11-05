package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Ein Mitarbeiter-Zugang: das Token steckt im Scanner-Geraet. Der Wert ist
 * die Zugangsberechtigung selbst - er gehoert in keine Antwort ausser der
 * Team-Verwaltung des Ladens.
 *
 * Rolle: MITARBEITER darf alles am Tresen (stempeln, Praemie einloesen,
 * Karte zuruecksetzen, Punkte buchen und korrigieren). AUSHILFE darf nur
 * stempeln und Einkaeufe buchen. Null = MITARBEITER, damit bestehende
 * Zugaenge in echten Laeden sich nicht veraendern.
 */
@Entity
@Table(name = "staff_tokens")
public class StaffToken {

    public static final String ROLLE_MITARBEITER = "MITARBEITER";
    public static final String ROLLE_AUSHILFE = "AUSHILFE";
    // Tablet an der Theke, der Kunde stempelt selbst (KioskService). Darf
    // nur stempeln: keine Praemie, keine Punkte, kein Aufladen, kein Reset.
    public static final String ROLLE_KIOSK = "KIOSK";
    public static final String NUR_PERSONAL_TEXT = "Das macht das Personal am Tresen.";
    public static final String NUR_MITARBEITER_TEXT =
            "Dieser Zugang darf nur stempeln. Dafür braucht es einen Mitarbeiter-Zugang.";

    @Id
    @Column(length = 128)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // Null = MITARBEITER (siehe Klassenkommentar).
    @Column(length = 20)
    private String rolle;

    // Wann zuletzt damit gestempelt oder gebucht wurde (StaffTokenFilter,
    // hoechstens alle paar Minuten geschrieben). Null = noch nie.
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    // Filiale (ShopOrt.id), zu der dieser Zugang gehoert. Null = Hauptstandort
    // bzw. "nicht zugeordnet" - dann entscheidet beim Scan der Geraeteort
    // (FilialenService). Nur die ID, keine Beziehung: der Zugang wird im
    // Filter ausserhalb jeder Transaktion gelesen.
    @Column(name = "ort_id", length = 64)
    private String ortId;

    protected StaffToken() {}

    public static StaffToken create(Shop shop, String label) {
        return create(shop, label, null);
    }

    public static StaffToken create(Shop shop, String label, String rolle) {
        StaffToken t = new StaffToken();
        t.token = UUID.randomUUID().toString().replace("-", "");
        t.shop = shop;
        t.label = label;
        t.createdAt = Instant.now();
        t.rolle = rolleSauber(rolle);
        return t;
    }

    /** Leer = MITARBEITER. Unbekannt = IllegalArgumentException (400). */
    public static String rolleSauber(String rolle) {
        if (rolle == null || rolle.isBlank()) return ROLLE_MITARBEITER;
        String r = rolle.trim().toUpperCase(Locale.ROOT);
        if (!ROLLE_MITARBEITER.equals(r) && !ROLLE_AUSHILFE.equals(r) && !ROLLE_KIOSK.equals(r)) {
            throw new IllegalArgumentException("Unbekannte Rolle: " + rolle);
        }
        return r;
    }

    public String getToken() { return token; }
    public Shop getShop() { return shop; }
    public String getLabel() { return label; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }

    public String getRolle() { return rolle != null ? rolle : ROLLE_MITARBEITER; }

    /**
     * Aushilfe = nur stempeln und Einkaeufe buchen. Die Controller fragen
     * bewusst DIESE Seite ab: ein Zugang ohne Rolle (alte Zeilen, Mocks in
     * Tests) ist keine Aushilfe und darf alles wie bisher.
     */
    public boolean istAushilfe() { return ROLLE_AUSHILFE.equals(getRolle()); }

    /** Tablet zum Selbst-Stempeln: nur Stempel, alles andere macht das Personal. */
    public boolean istKiosk() { return ROLLE_KIOSK.equals(getRolle()); }

    /** Praemie einloesen, Karte zuruecksetzen, Punkte korrigieren: nur MITARBEITER. */
    public boolean darfEinloesen() { return !istAushilfe() && !istKiosk(); }

    public void umbenennen(String label) { this.label = label; }
    public void rolle(String rolle) { this.rolle = rolleSauber(rolle); }
    public void benutzt(Instant jetzt) { this.lastUsedAt = jetzt; }

    /** Null = Hauptstandort. Ob der Ort zum Laden gehoert, prueft der TeamService. */
    public String getOrtId() { return ortId; }
    public void ort(String ortId) { this.ortId = (ortId == null || ortId.isBlank()) ? null : ortId; }
}
