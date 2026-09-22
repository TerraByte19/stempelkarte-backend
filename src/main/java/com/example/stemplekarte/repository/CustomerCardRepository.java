package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CustomerCardRepository extends JpaRepository<CustomerCard, String> {

    @EntityGraph(attributePaths = {"card", "card.shop", "customer"})
    Optional<CustomerCard> findById(String id);

    Optional<CustomerCard> findByCustomerAndCard(Customer customer, Card card);

    // Kundenkarte ueber die beiden IDs aus dem QR-Code. Karte und Kunde werden
    // mitgeladen, weil der Aufrufer beide braucht und open-in-view aus ist.
    @EntityGraph(attributePaths = {"card", "card.shop", "customer"})
    Optional<CustomerCard> findByCustomer_IdAndCard_Id(String customerId, String cardId);

    List<CustomerCard> findByCustomer(Customer customer);
    List<CustomerCard> findByCard(Card card);
    Optional<CustomerCard> findByAuthTokenAndCard(String authToken, Card card);
    int countByCard_Shop(Shop shop);

    // Anzahl UNTERSCHIEDLICHER Personen eines Ladens (ueber alle Karten, aktiv
    // wie inaktiv). countByCard_Shop zaehlt customer_card-Zeilen - bei Laeden
    // mit mehreren Karten also dieselbe Person mehrfach.
    @Query("select count(distinct cc.customer.id) from CustomerCard cc where cc.card.shop = :shop")
    long countDistinctCustomersByShop(Shop shop);

    // Personen, die mindestens einmal eine Belohnung eingeloest haben (fuer die
    // Einloese-Quote - customer_card-Zeilen zu zaehlen kann bei Mehrfachkarten
    // ueber 100% gehen).
    @Query("select count(distinct cc.customer.id) from CustomerCard cc "
            + "where cc.card.shop = :shop and cc.totalRewards > 0")
    long countDistinctCustomersWithRewardByShop(Shop shop);

    // Newsletter: alle Karten eines Ladens, deren Kunde Werbung zugestimmt hat.
    // card wird mitgeladen, weil die Mail den Stempelstand nennt und
    // open-in-view aus ist - sonst knallt der Zugriff spaeter.
    @EntityGraph(attributePaths = {"customer", "card"})
    List<CustomerCard> findByCard_ShopAndMarketingConsentTrue(Shop shop);
}