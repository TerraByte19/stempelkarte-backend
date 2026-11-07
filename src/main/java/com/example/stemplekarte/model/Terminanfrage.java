package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Eine Terminanfrage fuer eine Vorfuehrung von der Startseite: im Laden,
 * am Telefon oder per Video. Wie die Kontaktanfrage landet sie nur im
 * Admin-Panel; bestaetigt wird von Hand (WhatsApp, Anruf, Mail), denn es
 * gibt keinen Kalender, gegen den der Server pruefen koennte.
 */
@Entity
@Table(name = "terminanfragen")
public class Terminanfrage {

    public enum Art { IM_LADEN, TELEFON, VIDEO }
    public enum Status { OFFEN, BESTAETIGT, ERLEDIGT }

    @Id
    @Column(length = 40)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 200, nullable = false)
    private String laden;

    @Column(length = 200)
    private String email;

    @Column(length = 40)
    private String telefon;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Art art;

    @Column(name = "wunsch_am", nullable = false)
    private LocalDate wunschAm;

    /** "HH:mm", Berliner Zeit. */
    @Column(name = "wunsch_zeit", length = 5, nullable = false)
    private String wunschZeit;

    @Column(length = 300)
    private String adresse;

    @Column(length = 1000)
    private String nachricht;

    /** Sprache der Seite beim Absenden (de/en/ar/tr), damit die Antwort passt. */
    @Column(length = 5)
    private String sprache;

    @Column(name = "eingegangen_am", nullable = false)
    private Instant eingegangenAm;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Status status;

    protected Terminanfrage() {}

    public static Terminanfrage create(String name, String laden, String email, String telefon, Art art,
                                       LocalDate wunschAm, String wunschZeit, String adresse, String nachricht,
                                       String sprache, Instant jetzt) {
        Terminanfrage t = new Terminanfrage();
        t.id = "TER-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        t.name = name;
        t.laden = laden;
        t.email = email;
        t.telefon = telefon;
        t.art = art;
        t.wunschAm = wunschAm;
        t.wunschZeit = wunschZeit;
        t.adresse = adresse;
        t.nachricht = nachricht;
        t.sprache = sprache;
        t.eingegangenAm = jetzt;
        t.status = Status.OFFEN;
        return t;
    }

    public void setStatus(Status status) { this.status = status; }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getLaden() { return laden; }
    public String getEmail() { return email; }
    public String getTelefon() { return telefon; }
    public Art getArt() { return art; }
    public LocalDate getWunschAm() { return wunschAm; }
    public String getWunschZeit() { return wunschZeit; }
    public String getAdresse() { return adresse; }
    public String getNachricht() { return nachricht; }
    public String getSprache() { return sprache; }
    public Instant getEingegangenAm() { return eingegangenAm; }
    public Status getStatus() { return status; }
}
