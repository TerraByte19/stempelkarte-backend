package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Anfrage aus dem Kontaktformular der Landing-Page.
 *
 * <p>Wird gespeichert, damit sie im Admin-Panel steht - die Mail an den
 * Betreiber ist nur der Hinweis. Kommt die Mail nicht an (Tageslimit, Brevo
 * hakt, MAIL_FROM fehlt), geht die Anfrage trotzdem nicht verloren.
 */
@Entity
@Table(name = "kontaktanfrage")
public class Kontaktanfrage {

    @Id
    @Column(length = 40)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(name = "laden", length = 200, nullable = false)
    private String laden;

    @Column(length = 200, nullable = false)
    private String email;

    @Column(length = 4000, nullable = false)
    private String nachricht;

    @Column(name = "eingegangen_am", nullable = false)
    private Instant eingegangenAm;

    /** Abgehakt im Admin-Panel. Erledigte bleiben stehen, nur grau. */
    @Column(nullable = false)
    private boolean erledigt;

    protected Kontaktanfrage() {}

    public static Kontaktanfrage create(String name, String laden, String email, String nachricht) {
        Kontaktanfrage k = new Kontaktanfrage();
        k.id = "KON-" + UUID.randomUUID();
        k.name = name;
        k.laden = laden;
        k.email = email;
        k.nachricht = nachricht;
        k.eingegangenAm = Instant.now();
        k.erledigt = false;
        return k;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getLaden() { return laden; }
    public String getEmail() { return email; }
    public String getNachricht() { return nachricht; }
    public Instant getEingegangenAm() { return eingegangenAm; }
    public boolean isErledigt() { return erledigt; }

    public void setErledigt(boolean erledigt) { this.erledigt = erledigt; }
}
