package com.example.stemplekarte.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine eigene Automation des Ladens (Baukasten): Ausloeser plus Zahl,
 * optional auf eine Karte und eine Kundengruppe eingeschraenkt, und der
 * Text, der dann als Kampagne rausgeht. Pausieren = aktiv aus.
 *
 * Wer schon dran war, steht nicht hier, sondern in den Kampagnen, die der
 * Automat gestartet hat (Kampagne.automationId + Empfaengerzeilen).
 */
@Entity
@Table(name = "automationen",
        indexes = @Index(name = "idx_automation_shop", columnList = "shop_id"))
public class Automation {

    public static final int NAME_MAX = 40;

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Column(nullable = false)
    private boolean aktiv;

    // service.AutomationAusloeser.name()
    @Column(nullable = false, length = 24)
    private String ausloeser;

    @Column(nullable = false)
    private int wert;

    // Nur diese Karte (Card.id). Null = alle Karten des Ladens.
    @Column(name = "card_id", length = 64)
    private String cardId;

    // Nur diese Kundengruppe (service.Kundengruppe.name()). Null = alle.
    @Column(length = 20)
    private String gruppe;

    @Column(nullable = false, length = Kampagne.TEXT_MAX)
    private String text;

    @Column(name = "erstellt_am", nullable = false)
    private Instant erstelltAm;

    @Column(name = "geaendert_am", nullable = false)
    private Instant geaendertAm;

    // Letzter Lauf, der eine Kampagne gestartet hat, und wie viele Karten
    // insgesamt eine Nachricht bekamen.
    @Column(name = "zuletzt_am")
    private Instant zuletztAm;

    @Column(nullable = false)
    private int gesendet;

    protected Automation() {}

    public static Automation neu(Shop shop, String name, String ausloeser, int wert, String cardId,
                                 String gruppe, String text, boolean aktiv, Instant jetzt) {
        Automation a = new Automation();
        a.id = "AU-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        a.shop = shop;
        a.name = name;
        a.ausloeser = ausloeser;
        a.wert = wert;
        a.cardId = cardId;
        a.gruppe = gruppe;
        a.text = text;
        a.aktiv = aktiv;
        a.erstelltAm = jetzt;
        a.geaendertAm = jetzt;
        a.gesendet = 0;
        return a;
    }

    /** Geprueft vom AutomationService; null = Feld nicht anfassen (ausser cardId/gruppe: "" = loeschen). */
    public void aendern(String name, String ausloeser, Integer wert, String cardId, String gruppe,
                        String text, Boolean aktiv, Instant jetzt) {
        if (name != null) this.name = name;
        if (ausloeser != null) this.ausloeser = ausloeser;
        if (wert != null) this.wert = wert;
        if (cardId != null) this.cardId = cardId.isBlank() ? null : cardId;
        if (gruppe != null) this.gruppe = gruppe.isBlank() ? null : gruppe;
        if (text != null) this.text = text;
        if (aktiv != null) this.aktiv = aktiv;
        this.geaendertAm = jetzt;
    }

    public void gelaufen(Instant jetzt, int anzahl) {
        this.zuletztAm = jetzt;
        this.gesendet += Math.max(0, anzahl);
    }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getName() { return name; }
    public boolean isAktiv() { return aktiv; }
    public String getAusloeser() { return ausloeser; }
    public int getWert() { return wert; }
    public String getCardId() { return cardId; }
    public String getGruppe() { return gruppe; }
    public String getText() { return text; }
    public Instant getErstelltAm() { return erstelltAm; }
    public Instant getGeaendertAm() { return geaendertAm; }
    public Instant getZuletztAm() { return zuletztAm; }
    public int getGesendet() { return gesendet; }
}
