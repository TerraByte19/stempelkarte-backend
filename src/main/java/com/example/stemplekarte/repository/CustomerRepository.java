package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, String> {
    Optional<Customer> findByEmail(String email);
    boolean existsByEmail(String email);

    // Für den Bestätigungs-Link (Double-Opt-In)
    Optional<Customer> findByConfirmToken(String confirmToken);

    // Für den endgültigen Lösch-Link (Schritt 2)
    Optional<Customer> findByDeleteToken(String deleteToken);
}