package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ShopRepository extends JpaRepository<Shop, String> {
    Optional<Shop> findByEmail(String email);
    boolean existsByEmail(String email);

    // Reihenfolge des Admin-Panels. Name als zweites Kriterium, damit Laeden
    // mit gleichem sortOrder (Bestandslaeden stehen alle auf 0) wenigstens
    // stabil und lesbar sortiert sind statt in Datenbank-Reihenfolge.
    java.util.List<Shop> findAllByOrderBySortOrderAscNameAsc();
}