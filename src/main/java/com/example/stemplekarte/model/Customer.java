package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Customer() {}

    public static Customer create(String name, String email) {
        Customer c = new Customer();
        c.id = "CUST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        c.name = name;
        c.email = email.toLowerCase().trim();
        c.createdAt = Instant.now();
        return c;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
}