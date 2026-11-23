package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShopOrtRepository extends JpaRepository<ShopOrt, String> {

    List<ShopOrt> findByShopOrderByCreatedAtAsc(Shop shop);

    long countByShop(Shop shop);

    // Admin: alle Filialen, die auf die Bestaetigung warten - MIT Laden,
    // weil der Name des Ladens dazugehoert und die Sitzung danach zu ist.
    @org.springframework.data.jpa.repository.Query(
            "select o from ShopOrt o join fetch o.shop where o.bestaetigt = false order by o.createdAt asc")
    List<ShopOrt> findOffene();

    Optional<ShopOrt> findByIdAndShop(String id, Shop shop);

    // Beim Loeschen eines Ladens (AdminController#deleteShop).
    void deleteByShop(Shop shop);
}
