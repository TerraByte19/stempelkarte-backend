package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.Stimme;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StimmeRepository extends JpaRepository<Stimme, String> {

    Optional<Stimme> findByShop(Shop shop);

    // Startseite: freigegebene Stimmen, zuletzt freigegebene zuerst. Der Laden
    // wird mitgeladen, weil die Seite seinen Namen zeigt und open-in-view aus ist.
    @EntityGraph(attributePaths = "shop")
    List<Stimme> findTop6ByStatusOrderByEntschiedenAmDesc(Stimme.Status status);

    // Admin: alles, neueste Aenderung zuerst.
    @EntityGraph(attributePaths = "shop")
    List<Stimme> findAllByOrderByGeaendertAmDesc();

    @EntityGraph(attributePaths = "shop")
    Optional<Stimme> findById(String id);
}
