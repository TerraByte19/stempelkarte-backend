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

    // NUR DAS HIER IST NEU: Zwingt Hibernate, Karte, Shop und Kunde sofort zu laden.
    // Das repariert den Google- und Apple-Fehler im Hintergrund, ohne andere Dateien zu berühren!
    @EntityGraph(attributePaths = {"card", "card.shop", "customer"})
    Optional<CustomerCard> findById(String id);

    // Deine bestehenden Methoden bleiben absolut unverändert:
    Optional<CustomerCard> findByCustomerAndCard(Customer customer, Card card);
    List<CustomerCard> findByCustomer(Customer customer);
    List<CustomerCard> findByCard(Card card);
    Optional<CustomerCard> findByAuthTokenAndCard(String authToken, Card card);
    int countByCard_Shop(Shop shop);
}