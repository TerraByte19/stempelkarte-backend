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
    }

    public void redeemReward() {
        this.stamps = 0;
        this.totalRewards++;
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

    public String getId() { return id; }
    public Customer getCustomer() { return customer; }
    public Card getCard() { return card; }
    public int getStamps() { return stamps; }
    public int getTotalRewards() { return totalRewards; }
    public String getAuthToken() { return authToken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isMarketingConsent() { return marketingConsent; }
    public Instant getConsentAt() { return consentAt; }
}