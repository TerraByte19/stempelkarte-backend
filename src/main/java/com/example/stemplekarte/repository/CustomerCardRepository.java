package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerCardRepository extends JpaRepository<CustomerCard, String> {

    @EntityGraph(attributePaths = {"card", "card.shop", "customer"})
    Optional<CustomerCard> findById(String id);

    Optional<CustomerCard> findByCustomerAndCard(Customer customer, Card card);
    List<CustomerCard> findByCustomer(Customer customer);
    List<CustomerCard> findByCard(Card card);
    Optional<CustomerCard> findByAuthTokenAndCard(String authToken, Card card);
    int countByCard_Shop(Shop shop);

    // Newsletter: alle Karten eines Ladens, deren Kunde Werbung zugestimmt hat
    @EntityGraph(attributePaths = {"customer"})
    List<CustomerCard> findByCard_ShopAndMarketingConsentTrue(Shop shop);
}