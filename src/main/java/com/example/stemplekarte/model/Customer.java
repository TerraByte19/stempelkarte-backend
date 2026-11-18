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

    // Sprache der Kundenseiten und Mails (i18n.Texts): beim Anmelden vom
    // Laden uebernommen, danach stabil. Null (Altbestand) = Sprache des Ladens.
    @Column(name = "language", length = 5)
    private String language;

    // Geburtstag und Handynummer liegen bewusst NICHT hier, sondern an der
    // CustomerCard: der Kunde ist ladenuebergreifend (eine E-Mail, ein
    // Datensatz), die Angaben macht er aber gegenueber EINEM Laden.

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

    /**
     * Endung der Platzhalter-Adresse eines Kunden ohne E-Mail. Die Spalte
     * email ist in der Datenbank Pflicht und eindeutig, deshalb bekommt so
     * ein Kunde eine erfundene, eindeutige Adresse. ".invalid" ist dafuer
     * reserviert und nie zustellbar - trotzdem darf an diese Adresse nie
     * eine Mail gehen (siehe isOhneMail, EmailService).
     */
    public static final String OHNE_MAIL_ENDUNG = "@ohne-mail.stampit.invalid";

    /**
     * Kunde ohne Anmeldung (Laden hat "Karte ohne E-Mail" eingeschaltet):
     * kein Name, keine Adresse. Der Name bleibt leer (User 2026-11-19: kein
     * erfundenes "Kunde 12345") - Karte, Wallet und Kundenseite lassen das
     * Namensfeld dann ganz weg.
     */
    public static Customer createOhneMail() {
        Customer c = new Customer();
        String uuid = UUID.randomUUID().toString();
        c.id = "CUST-" + uuid.toUpperCase();
        c.name = "";
        c.email = "k-" + UUID.randomUUID().toString().toLowerCase() + OHNE_MAIL_ENDUNG;
        c.createdAt = Instant.now();
        // Kein Bestaetigungs-Token: es gibt nichts zu bestaetigen.
        return c;
    }

    /** Kunde ohne echte E-Mail-Adresse - nie anschreiben. */
    public boolean isOhneMail() {
        return email != null && email.endsWith(OHNE_MAIL_ENDUNG);
    }

    /** Adresse fuer Anzeige und Schnittstellen: null statt der Platzhalter-Adresse. */
    public String getEmailAnzeige() {
        return isOhneMail() ? null : email;
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public String getLanguage() { return language; }

    /** Nur beim ersten Anmelden gesetzt (leer bleibt leer, wenn lang null ist). */
    public void setLanguage(String lang) { this.language = lang; }

    /** Für Bestandskunden, die noch keinen Token haben (alte Datensätze). */
    public void ensureConfirmToken() {
        if (this.confirmToken == null) this.confirmToken = newToken();
    }

    /**
     * Erzwingt eine neue Mail-Bestätigung: setzt emailConfirmed zurück und
     * vergibt einen frischen Token. Wird bei JEDER neuen Kartenanmeldung
     * aufgerufen, damit der Kunde die Karte erst nach Klick auf den
     * Mail-Link erhält — unabhängig davon, ob er früher schon mal bestätigt
     * hatte.
     */
    public void requireNewConfirmation() {
        this.emailConfirmed = false;
        this.confirmToken = newToken();
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
    /**
     * Leer bei Kunden ohne Anmeldung - auch bei den ersten, die noch als
     * "Kunde 12345" gespeichert wurden.
     */
    public String getName() { return isOhneMail() ? "" : name; }

    /** Hat der Kunde einen Namen, den man anzeigen kann? */
    public boolean hatName() {
        String n = getName();
        return n != null && !n.isBlank();
    }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isEmailConfirmed() { return emailConfirmed; }
    public String getConfirmToken() { return confirmToken; }
    public String getDeleteToken() { return deleteToken; }
}