package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Kampagne;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KampagneRepository extends JpaRepository<Kampagne, String> {

    // Der Laeufer braucht den Laden (Name, nachrichtenSeit) ausserhalb einer
    // Session - open-in-view ist aus.
    @EntityGraph(attributePaths = {"shop"})
    List<Kampagne> findByStatus(String status);

    // Geplante, deren Zeitpunkt erreicht ist.
    @EntityGraph(attributePaths = {"shop"})
    List<Kampagne> findByStatusAndGeplantAmLessThanEqual(String status, java.time.Instant jetzt);

    List<Kampagne> findTop20ByShopOrderByErstelltAmDesc(Shop shop);

    // Abbrechen: nur die eigene.
    java.util.Optional<Kampagne> findByIdAndShop(String id, Shop shop);

    // Beim Loeschen eines Ladens (AdminController#deleteShop).
    List<Kampagne> findByShop(Shop shop);
    void deleteByShop(Shop shop);
}
