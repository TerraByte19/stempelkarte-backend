package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Ein Ereignis in der Webhook-Ausgangstabelle. Entsteht in derselben
 * Transaktion wie der Stempel oder die Anmeldung; der Laeufer
 * (WebhookService) stellt es zu und wiederholt bei Fehlern.
 *
 * Der Laden steht als ID drin, nicht als Fremdschluessel: das Loeschen
 * eines Ladens soll nicht an alten Ereignissen haengen bleiben.
 */
@Entity
@Table(name = "webhook_ereignisse")
public class WebhookEreignis {

    public static final String OFFEN = "OFFEN";
    public static final String GESENDET = "GESENDET";
    public static final String FEHLER = "FEHLER";
    public static final int PAYLOAD_MAX = 4000;
    public static final int ANTWORT_MAX = 200;

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "shop_id", nullable = false, length = 40)
    private String shopId;

    @Column(nullable = false, length = 40)
    private String typ;

    @Column(nullable = false, length = PAYLOAD_MAX)
    private String payload;

    @Column(nullable = false, length = 12)
    private String status;

    @Column(nullable = false)
    private int versuche;

    @Column(name = "naechster_versuch", nullable = false)
    private Instant naechsterVersuch;

    @Column(name = "erstellt_am", nullable = false)
    private Instant erstelltAm;

    @Column(name = "gesendet_am")
    private Instant gesendetAm;

    @Column(length = ANTWORT_MAX)
    private String antwort;

    protected WebhookEreignis() {}

    public static WebhookEreignis neu(String id, String shopId, String typ, String payload, Instant jetzt) {
        WebhookEreignis e = new WebhookEreignis();
        e.id = id;
        e.shopId = shopId;
        e.typ = typ;
        e.payload = payload;
        e.status = OFFEN;
        e.versuche = 0;
        e.naechsterVersuch = jetzt;
        e.erstelltAm = jetzt;
        return e;
    }

    public void gesendet(Instant jetzt, String antwort) {
        this.versuche++;
        this.status = GESENDET;
        this.gesendetAm = jetzt;
        this.antwort = kurz(antwort);
    }

    /** Noch einmal spaeter, oder - nach dem letzten Versuch - FEHLER. */
    public void fehlgeschlagen(Instant naechster, String antwort, boolean letzter) {
        this.versuche++;
        this.antwort = kurz(antwort);
        if (letzter) {
            this.status = FEHLER;
        } else {
            this.naechsterVersuch = naechster;
        }
    }

    /** Webhook inzwischen entfernt: Ereignis still abhaken. */
    public void verworfen(String grund) {
        this.status = FEHLER;
        this.antwort = kurz(grund);
    }

    private static String kurz(String s) {
        if (s == null) return null;
        return s.length() > ANTWORT_MAX ? s.substring(0, ANTWORT_MAX) : s;
    }

    public String getId() { return id; }
    public String getShopId() { return shopId; }
    public String getTyp() { return typ; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getVersuche() { return versuche; }
    public Instant getNaechsterVersuch() { return naechsterVersuch; }
    public Instant getErstelltAm() { return erstelltAm; }
    public Instant getGesendetAm() { return gesendetAm; }
    public String getAntwort() { return antwort; }
}
