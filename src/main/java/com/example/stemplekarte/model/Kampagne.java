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

    @Column(name = "fertig_am")
    private Instant fertigAm;

    protected Kampagne() {}

    public static Kampagne starte(Shop shop, String text, Zielgruppe gruppe, int empfaenger, Instant jetzt) {
        Kampagne k = new Kampagne();
        k.id = "KP-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        k.shop = shop;
        k.text = text;
        k.gruppe = (gruppe != null ? gruppe : Zielgruppe.ALLE).name();
        k.status = LAEUFT;
        k.empfaenger = empfaenger;
        k.gesendet = 0;
        k.erstelltAm = jetzt;
        return k;
    }

    public void gesendet(int weitere) { this.gesendet += weitere; }

    public void beenden(Instant jetzt) {
        this.status = FERTIG;
        this.fertigAm = jetzt;
    }

    public boolean laeuft() { return LAEUFT.equals(status); }

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
