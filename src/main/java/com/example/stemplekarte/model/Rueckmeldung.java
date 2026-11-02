package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine private Rueckmeldung eines Kunden an den Laden: Sterne und ein
 * optionaler Satz. Geht nur an den Laden, nie nach aussen.
 *
 * Die Kundenkarte steht nur als Text-ID drin, nicht als Fremdschluessel:
 * wird die Karte geloescht (Kunde will raus), bleibt die Rueckmeldung ohne
 * Namen stehen und blockiert das Loeschen nicht.
 */
@Entity
@Table(name = "rueckmeldungen")
public class Rueckmeldung {

    public static final int TEXT_MAX = 1000;

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(name = "customer_card_id", length = 40, nullable = false)
    private String customerCardId;

    @Column(nullable = false)
    private int sterne;

    @Column(length = TEXT_MAX)
    private String text;

    @Column(name = "eingegangen_am", nullable = false)
    private Instant eingegangenAm;

    @Column(nullable = false)
    private boolean gelesen;

    protected Rueckmeldung() {}

    public static Rueckmeldung neu(Shop shop, String customerCardId, int sterne, String text, Instant jetzt) {
        Rueckmeldung r = new Rueckmeldung();
        r.id = "RM-" + UUID.randomUUID();
        r.shop = shop;
        r.customerCardId = customerCardId;
        r.sterne = sterne;
        r.text = (text == null || text.isBlank()) ? null : text.trim();
        r.eingegangenAm = jetzt;
        r.gelesen = false;
        return r;
    }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getCustomerCardId() { return customerCardId; }
    public int getSterne() { return sterne; }
    public String getText() { return text; }
    public Instant getEingegangenAm() { return eingegangenAm; }
    public boolean isGelesen() { return gelesen; }

    public void gelesen() { this.gelesen = true; }
}
