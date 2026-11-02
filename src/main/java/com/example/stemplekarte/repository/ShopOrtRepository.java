package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShopOrtRepository extends JpaRepository<ShopOrt, String> {

    List<ShopOrt> findByShopOrderByCreatedAtAsc(Shop shop);

    long countByShop(Shop shop);

    Optional<ShopOrt> findByIdAndShop(String id, Shop shop);

    // Beim Loeschen eines Ladens (AdminController#deleteShop).
    void deleteByShop(Shop shop);
}
