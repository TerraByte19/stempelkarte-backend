package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // ── E-Mail-Bestätigung (Double-Opt-In) ──────────────────────────────
    // columnDefinition mit Default, damit ddl-auto=update bei bestehenden
    // Kunden-Zeilen in Postgres nicht fehlschlägt (NOT NULL ohne Default).
    @Column(name = "email_confirmed", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean emailConfirmed;

    // Einmal-Token für den Bestätigungs-Link (wird nach Bestätigung geleert)
    @Column(name = "confirm_token", length = 64)
    private String confirmToken;

    // Einmal-Token für die endgültige Löschung (Schritt 2 des Lösch-Flows)
    @Column(name = "delete_token", length = 64)
    private String deleteToken;

    protected Customer() {}

    public static Customer create(String name, String email) {
        Customer c = new Customer();
        // Volle UUID statt nur 8 Hex-Zeichen: macht die Kunden-ID praktisch
        // unratbar (Enumeration-Schutz). Die ID wirkt damit als Capability-Token
        // in der Karten-URL. Bestehende Kunden behalten ihre alte kurze ID.
        c.id = "CUST-" + UUID.randomUUID().toString().toUpperCase();
        c.name = name;
        c.email = email.toLowerCase().trim();
        c.createdAt = Instant.now();
        c.confirmToken = newToken();
        return c;
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Für Bestandskunden, die noch keinen Token haben (alte Datensätze). */
    public void ensureConfirmToken() {
        if (this.confirmToken == null) this.confirmToken = newToken();
    }

    /** Klick auf den Bestätigungs-Link in der Mail. */
    public void confirmEmail() {
        this.emailConfirmed = true;
        this.confirmToken = null;
    }

    /** Schritt 1 des Lösch-Flows: erzeugt den Token für die zweite Sicherheits-Mail. */
    public String startDeletion() {
        this.deleteToken = newToken();
        return this.deleteToken;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isEmailConfirmed() { return emailConfirmed; }
    public String getConfirmToken() { return confirmToken; }
    public String getDeleteToken() { return deleteToken; }
}