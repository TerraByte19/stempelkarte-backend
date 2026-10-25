package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_cards",
        uniqueConstraints = @UniqueConstraint(columnNames = {"customer_id", "card_id"}))
public class CustomerCard {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    @Column(nullable = false)
    private int stamps;

    @Column(name = "total_rewards", nullable = false)
    private int totalRewards;

    // Punktestand in Hundertstel-Punkten (520 = 5,20 Punkte). Gilt nur fuer
    // Karten vom Typ POINTS; Stempelkarten lassen die Spalte auf 0 stehen.
    // Default in der Spaltendefinition, damit ddl-auto=update bei
    // bestehenden Zeilen nicht fehlschlaegt.
    @Column(name = "points_x100", nullable = false,
            columnDefinition = "bigint not null default 0")
    private long pointsX100;

    @Column(name = "auth_token", nullable = false, length = 128)
    private String authToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── Werbe-Einwilligung (pro Laden/Karte, DSGVO + UWG) ───────────────
    // Checkbox "Ich möchte Angebote per E-Mail erhalten" bei der Anmeldung.
    // columnDefinition mit Default, damit ddl-auto=update bei bestehenden
    // Zeilen in Postgres nicht fehlschlägt.
    @Column(name = "marketing_consent", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean marketingConsent;

    // Zeitpunkt der Einwilligung (Nachweis für Double-Opt-In)
    @Column(name = "consent_at")
    private Instant consentAt;

    // ── Rueckhol-Nachricht (RueckholService) ─────────────────────────────
    // Letzter Besuch = letzte Buchung durch das Personal. updatedAt taugt
    // dafuer nicht: das aendert sich auch beim Verschicken der Nachricht.
    // Null bei Karten von vor dieser Spalte - dann gilt updatedAt.
    @Column(name = "last_visit_at")
    private Instant lastVisitAt;

    @Column(name = "winback_sent_at")
    private Instant winbackSentAt;

    // Der Text, wie er verschickt wurde. Steht im Pass, bis der Kunde
    // wiederkommt - aendert der Laden seinen Text, bleibt die alte Nachricht.
    @Column(name = "winback_text", length = 300)
    private String winbackText;

    protected CustomerCard() {}

    public static CustomerCard create(Customer customer, Card card) {
        CustomerCard cc = new CustomerCard();
        cc.id = "CC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        cc.customer = customer;
        cc.card = card;
        cc.stamps = 0;
        cc.totalRewards = 0;
        cc.authToken = UUID.randomUUID().toString().replace("-", "");
        cc.createdAt = Instant.now();
        cc.updatedAt = cc.createdAt;
        return cc;
    }

    public void addStamp() {
        this.stamps++;
        this.updatedAt = Instant.now();
        this.lastVisitAt = this.updatedAt;
    }

    /**
     * Loest eine Belohnung ein. Abgezogen wird genau die Schwelle, nicht auf 0
     * gesetzt: senkt ein Laden die Stempelzahl nachtraeglich (z.B. 10 -> 5),
     * hat ein Kunde ploetzlich mehr Stempel als noetig. Der Rest bleibt stehen
     * und zaehlt fuer die naechste Belohnung, statt zu verfallen.
     *
     * Im Normalfall (Karte genau voll) ist das Ergebnis unveraendert 0.
     */
    public void redeemReward(int threshold) {
        this.stamps = Math.max(0, this.stamps - Math.max(1, threshold));
        this.totalRewards++;
        this.updatedAt = Instant.now();
        this.lastVisitAt = this.updatedAt;
    }

    /**
     * Setzt die Karte komplett zurück wie eine neue Karte:
     * Stempel UND Belohnungszähler auf 0. Wird vom Reset-Button im Scanner
     * genutzt. Marketing-Einwilligung bleibt unangetastet.
     */
    public void resetAll() {
        this.stamps = 0;
        this.totalRewards = 0;
        this.pointsX100 = 0;
        this.updatedAt = Instant.now();
    }

    /**
     * Bucht Punkte auf oder ab und gibt zurueck, wie viel TATSAECHLICH
     * gebucht wurde.
     *
     * Der Bestand geht nie unter null - gleiches Muster wie redeemReward bei
     * den Stempeln. Bei einer Korrektur ueber mehr als den Bestand faellt
     * der Rueckgabewert deshalb kleiner aus als der Wunsch, und genau dieser
     * Rueckgabewert landet in der Buchungszeile: sie soll festhalten, was
     * passiert ist, nicht was gemeint war.
     */
    public long addPoints(long deltaX100) {
        long vorher = this.pointsX100;
        this.pointsX100 = Math.max(0, vorher + deltaX100);
        this.updatedAt = Instant.now();
        this.lastVisitAt = this.updatedAt;
        return this.pointsX100 - vorher;
    }

    public boolean kannBezahlen(long kostenX100) {
        return this.pointsX100 >= kostenX100;
    }

    /** Belohnung zaehlen, ohne am Stempelstand zu ruehren. Punktekarten
     *  ziehen den Preis ueber addPoints ab, nicht ueber eine Schwelle. */
    public void zaehleBelohnung() {
        this.totalRewards++;
        this.updatedAt = Instant.now();
        this.lastVisitAt = this.updatedAt;
    }

    /** Ruecknahme einer Einloesung. Geht nie unter null - sonst stuende auf
     *  einer Karte eine negative Zahl eingeloester Praemien. */
    public void nimmBelohnungZurueck() {
        this.totalRewards = Math.max(0, this.totalRewards - 1);
        this.updatedAt = Instant.now();
    }

    /** Checkbox bei der Anmeldung angekreuzt. */
    public void giveMarketingConsent() {
        this.marketingConsent = true;
        this.consentAt = Instant.now();
    }

    /** Klick auf den Abmelde-Link in einer Werbe-Mail. */
    public void revokeMarketingConsent() {
        this.marketingConsent = false;
    }

    public Instant letzterBesuch() {
        return lastVisitAt != null ? lastVisitAt : updatedAt;
    }

    /**
     * Seit vor der Grenze nicht mehr da UND seit dem letzten Besuch noch
     * keine Nachricht bekommen. Pro Pause also genau eine Nachricht.
     */
    public boolean rueckholFaellig(Instant grenze) {
        Instant besuch = letzterBesuch();
        return besuch != null && besuch.isBefore(grenze)
                && (winbackSentAt == null || winbackSentAt.isBefore(besuch));
    }

    /** Nachricht verschickt und der Kunde war seitdem nicht da. */
    public boolean rueckholSichtbar() {
        return winbackSentAt != null && winbackText != null
                && (lastVisitAt == null || winbackSentAt.isAfter(lastVisitAt));
    }

    /**
     * Pass neu ausliefern lassen, ohne etwas zu melden: bringt das
     * Nachrichtenfeld aufs Handy, bevor die erste Nachricht kommt. Der letzte
     * Besuch wird vorher festgehalten, sonst saehe updatedAt wie einer aus.
     */
    public void passAuffrischen(Instant jetzt) {
        if (lastVisitAt == null) lastVisitAt = updatedAt;
        this.updatedAt = jetzt;
    }

    public void rueckholGesendet(String text, Instant jetzt) {
        if (lastVisitAt == null) lastVisitAt = updatedAt;
        this.winbackText = text;
        this.winbackSentAt = jetzt;
        // updatedAt bewegen, damit iOS den Pass mit der Nachricht abholt.
        this.updatedAt = jetzt;
    }

    public String getId() { return id; }
    public Customer getCustomer() { return customer; }
    public Card getCard() { return card; }
    public int getStamps() { return stamps; }
    public int getTotalRewards() { return totalRewards; }
    public long getPointsX100() { return pointsX100; }
    public String getAuthToken() { return authToken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isMarketingConsent() { return marketingConsent; }
    public Instant getConsentAt() { return consentAt; }
    public Instant getLastVisitAt() { return lastVisitAt; }
    public Instant getWinbackSentAt() { return winbackSentAt; }
    public String getWinbackText() { return winbackText; }
}