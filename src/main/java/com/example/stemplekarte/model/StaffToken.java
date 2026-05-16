package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "staff_tokens")
public class StaffToken {

    @Id
    @Column(length = 128)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StaffToken() {}

    public static StaffToken create(Shop shop, String label) {
        StaffToken t = new StaffToken();
        t.token = UUID.randomUUID().toString().replace("-", "");
        t.shop = shop;
        t.label = label;
        t.createdAt = Instant.now();
        return t;
    }

    public String getToken() { return token; }
    public Shop getShop() { return shop; }
    public String getLabel() { return label; }
    public Instant getCreatedAt() { return createdAt; }
}