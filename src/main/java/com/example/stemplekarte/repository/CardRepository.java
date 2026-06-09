package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CardRepository extends JpaRepository<Card, String> {

    @EntityGraph(attributePaths = {"shop"})
    Optional<Card> findById(String id);

    List<Card> findByShopAndActiveTrue(Shop shop);

    // Für vollständige Shop-Löschung (auch inaktive Karten)
    List<Card> findByShop(Shop shop);
}