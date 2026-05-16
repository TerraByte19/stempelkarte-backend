package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CardRepository extends JpaRepository<Card, String> {
    List<Card> findByShopAndActiveTrue(Shop shop);
}