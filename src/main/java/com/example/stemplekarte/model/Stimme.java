package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Die Stimme eines Ladens fuer die Startseite ("Das sagen Laeden, die
 * Stampit nutzen"). Der Laden schreibt sie in der App, der Admin gibt sie
 * frei, erst dann holt die Website sie ab.
 *
 * Eine Stimme je Laden: aendert der Laden sie, geht sie wieder auf OFFEN und
 * wartet erneut auf Freigabe. Das Einverstaendnis (Name des Ladens und Ort
 * duerfen auf stampit-app.de stehen) wird mit Zeitstempel gespeichert - das
 * ist der Beleg, dass das Zitat echt und freigegeben ist.
 */
@Entity
@Table(name = "stimmen")
public class Stimme {

    public static final int ZITAT_MIN = 10;
    public static final int ZITAT_MAX = 320;
    public static final int PERSON_MAX = 100;
    public static final int ORT_MAX = 80;

    public enum Status { OFFEN, FREIGEGEBEN, ABGELEHNT }

    @Id
    @Column(length = 40)
    private String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false, unique = true)
    private Shop shop;

    @Column(length = ZITAT_MAX, nullable = false)
    private String zitat;

    /** Vorname und Rolle, z. B. "Ayşe, Inhaberin". Optional. */
    @Column(length = PERSON_MAX)
    private String person;

    @Column(length = ORT_MAX, nullable = false)
    private String ort;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Status status;

    @Column(name = "einverstanden_am", nullable = false)
    private Instant einverstandenAm;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "geaendert_am", nullable = false)
    private Instant geaendertAm;

    @Column(name = "entschieden_am")
    private Instant entschiedenAm;

    protected Stimme() {}

    public static Stimme create(Shop shop, String zitat, String person, String ort, Instant jetzt) {
        Stimme s = new Stimme();
        s.id = "STM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        s.shop = shop;
        s.createdAt = jetzt;
        s.aendern(zitat, person, ort, jetzt);
        return s;
    }

    /** Neuer Text = neue Pruefung: zurueck auf OFFEN, Entscheidung weg, Einverstaendnis frisch. */
    public void aendern(String zitat, String person, String ort, Instant jetzt) {
        this.zitat = zitat;
        this.person = person;
        this.ort = ort;
        this.status = Status.OFFEN;
        this.einverstandenAm = jetzt;
        this.geaendertAm = jetzt;
        this.entschiedenAm = null;
    }

    public void freigeben(Instant jetzt) {
        this.status = Status.FREIGEGEBEN;
        this.entschiedenAm = jetzt;
    }

    public void ablehnen(Instant jetzt) {
        this.status = Status.ABGELEHNT;
        this.entschiedenAm = jetzt;
    }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getZitat() { return zitat; }
    public String getPerson() { return person; }
    public String getOrt() { return ort; }
    public Status getStatus() { return status; }
    public Instant getEinverstandenAm() { return einverstandenAm; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getGeaendertAm() { return geaendertAm; }
    public Instant getEntschiedenAm() { return entschiedenAm; }
}
