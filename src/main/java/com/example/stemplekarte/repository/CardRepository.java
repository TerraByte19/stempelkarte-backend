package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CardRepository extends JpaRepository<Card, String> {

    // DER FIX: Lädt die Karte und den zugehörigen Shop sofort zusammen.
    // Das verhindert die LazyInitializationException (no session) im LandingController.
    @EntityGraph(attributePaths = {"shop"})
    Optional<Card> findById(String id);

    // Deine bestehende Methode bleibt absolut unverändert:
    List<Card> findByShopAndActiveTrue(Shop shop);
}