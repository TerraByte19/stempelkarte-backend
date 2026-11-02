package com.example.stemplekarte.model;

import com.example.stemplekarte.service.Zielgruppe;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Push-Kampagne: ein Text vom Laden an eine Zielgruppe seiner Kunden.
 *
 * Wer sie bekommt, steht in kampagne_empfaenger (eine Zeile pro Karte, beim
 * Start festgelegt). Der Fortschritt steht dort, nicht hier. Stirbt der
 * Server mittendrin (Deploy), laeuft die Kampagne nach dem Neustart einfach
 * weiter - anders als beim Newsletter weiss jede Zeile selbst, ob sie dran war.
 */
@Entity
@Table(name = "kampagnen",
        indexes = @Index(name = "idx_kampagne_shop_zeit", columnList = "shop_id, erstellt_am"))
public class Kampagne {

    public static final String GEPLANT = "SCHEDULED";
    public static final String LAEUFT = "RUNNING";
    public static final String FERTIG = "DONE";
    public static final int TEXT_MAX = 160;

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false, length = TEXT_MAX)
    private String text;

    // Zielgruppe.name(). Null bei alten Zeilen = ALLE.
    @Column(length = 24)
    private String gruppe;

    @Column(nullable = false, length = 16)
    private String status;

    // Karten, die beim Start in Frage kamen.
    @Column(nullable = false)
    private int empfaenger;

    // Karten, die den Text tatsaechlich bekommen haben.
    @Column(nullable = false)
    private int gesendet;

    @Column(name = "erstellt_am", nullable = false)
    private Instant erstelltAm;

    // Geplant: Versand erst zu diesem Zeitpunkt. Die Empfaenger werden DANN
    // bestimmt, nicht beim Anlegen - "30 Tage nicht da" soll die treffen,
    // die es zum Versand sind. Null = sofort.
    @Column(name = "geplant_am")
    private Instant geplantAm;

    @Column(name = "fertig_am")
    private Instant fertigAm;

    // Ergebnis: wie viele Empfaenger in den 7 Tagen nach dem Versand mindestens
    // einmal gescannt haben. Null = noch nicht festgeschrieben (das Dashboard
    // rechnet solange live), ergebnisAm = wann es fest wurde.
    @Column(name = "wiedergekommen")
    private Integer wiedergekommen;

    @Column(name = "ergebnis_am")
    private Instant ergebnisAm;

    protected Kampagne() {}

    public static Kampagne starte(Shop shop, String text, Zielgruppe gruppe, int empfaenger, Instant jetzt) {
        return starte(shop, text, gruppe, empfaenger, jetzt, null);
    }

    /** geplantAm != null: Status GEPLANT, empfaenger ist nur eine Schaetzung. */
    public static Kampagne starte(Shop shop, String text, Zielgruppe gruppe, int empfaenger,
                                  Instant jetzt, Instant geplantAm) {
        Kampagne k = new Kampagne();
        k.id = "KP-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        k.shop = shop;
        k.text = text;
        k.gruppe = (gruppe != null ? gruppe : Zielgruppe.ALLE).name();
        k.status = geplantAm != null ? GEPLANT : LAEUFT;
        k.empfaenger = empfaenger;
        k.gesendet = 0;
        k.erstelltAm = jetzt;
        k.geplantAm = geplantAm;
        return k;
    }

    /** Geplante Kampagne wird faellig: ab jetzt laeuft sie mit der echten Empfaengerzahl. */
    public void aktivieren(int empfaenger) {
        this.status = LAEUFT;
        this.empfaenger = empfaenger;
    }

    public void gesendet(int weitere) { this.gesendet += weitere; }

    public void ergebnis(int wiedergekommen, Instant jetzt) {
        this.wiedergekommen = wiedergekommen;
        this.ergebnisAm = jetzt;
    }

    /** Ab wann die Nachricht draussen war: der geplante Zeitpunkt, sonst das Anlegen. */
    public Instant startAm() {
        return geplantAm != null ? geplantAm : erstelltAm;
    }

    public boolean ergebnisFest() { return ergebnisAm != null; }
    public Integer getWiedergekommen() { return wiedergekommen; }
    public Instant getErgebnisAm() { return ergebnisAm; }

    public void beenden(Instant jetzt) {
        this.status = FERTIG;
        this.fertigAm = jetzt;
    }

    public boolean laeuft() { return LAEUFT.equals(status); }
    public boolean istGeplant() { return GEPLANT.equals(status); }
    public Instant getGeplantAm() { return geplantAm; }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getText() { return text; }
    public String getGruppe() { return gruppe != null ? gruppe : Zielgruppe.ALLE.name(); }
    public String getStatus() { return status; }
    public int getEmpfaenger() { return empfaenger; }
    public int getGesendet() { return gesendet; }
    public Instant getErstelltAm() { return erstelltAm; }
    public Instant getFertigAm() { return fertigAm; }
}
