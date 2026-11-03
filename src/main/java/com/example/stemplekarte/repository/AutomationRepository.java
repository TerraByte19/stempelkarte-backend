package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Automation;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AutomationRepository extends JpaRepository<Automation, String> {

    List<Automation> findByShopOrderByErstelltAmAsc(Shop shop);

    long countByShop(Shop shop);

    Optional<Automation> findByIdAndShop(String id, Shop shop);

    // Der Laeufer braucht den Laden ausserhalb einer Session (open-in-view aus).
    @EntityGraph(attributePaths = {"shop"})
    List<Automation> findByAktivTrue();

    // Beim Loeschen eines Ladens (AdminController#deleteShop).
    void deleteByShop(Shop shop);
}
