package com.example.stemplekarte.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Zeile pro Karte, die eine Kampagne bekommen soll. Beim Start der
 * Kampagne festgelegt, damit "an 30 Tage nicht da" genau die trifft, die es
 * in dem Moment waren - und nicht, wer eine Stunde spaeter zufaellig passt.
 *
 * Nur IDs, keine Beziehungen (wie ScanLog): das Loeschen einer Karte darf
 * nicht an einer alten Kampagne scheitern. Der Laeufer ueberspringt Zeilen,
 * deren Karte es nicht mehr gibt.
 */
@Entity
@Table(name = "kampagne_empfaenger",
        indexes = @Index(name = "idx_kampempf_offen", columnList = "kampagne_id, erledigt"))
public class KampagneEmpfaenger {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "kampagne_id", nullable = false, length = 64)
    private String kampagneId;

    @Column(name = "customer_card_id", nullable = false, length = 64)
    private String customerCardId;

    @Column(nullable = false)
    private boolean erledigt;

    // iPhone mit altem Pass: einmal still aufgefrischt, Text kommt im
    // naechsten Lauf. Gesetzt = nicht noch einmal auffrischen.
    @Column(name = "aufgefrischt_am")
    private Instant aufgefrischtAm;

    protected KampagneEmpfaenger() {}

    public static KampagneEmpfaenger neu(String kampagneId, String customerCardId) {
        KampagneEmpfaenger e = new KampagneEmpfaenger();
        e.id = "KE-" + UUID.randomUUID();
        e.kampagneId = kampagneId;
        e.customerCardId = customerCardId;
        e.erledigt = false;
        return e;
    }

    public void aufgefrischt(Instant jetzt) { this.aufgefrischtAm = jetzt; }
    public boolean istAufgefrischt() { return aufgefrischtAm != null; }
    public void erledigen() { this.erledigt = true; }

    public String getId() { return id; }
    public String getKampagneId() { return kampagneId; }
    public String getCustomerCardId() { return customerCardId; }
    public boolean isErledigt() { return erledigt; }
    public Instant getAufgefrischtAm() { return aufgefrischtAm; }
}
