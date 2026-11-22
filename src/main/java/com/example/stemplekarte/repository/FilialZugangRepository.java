package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FilialZugangRepository extends JpaRepository<FilialZugang, String> {

    // MIT Laden: der JwtAuthFilter liest ausserhalb jeder Transaktion.
    @Query("select z from FilialZugang z join fetch z.shop where z.id = :id")
    Optional<FilialZugang> findWithShopById(@Param("id") String id);

    @Query("select z from FilialZugang z join fetch z.shop where z.benutzername = :benutzername")
    Optional<FilialZugang> findWithShopByBenutzername(@Param("benutzername") String benutzername);

    boolean existsByBenutzername(String benutzername);

    List<FilialZugang> findByShopOrderByCreatedAtAsc(Shop shop);

    Optional<FilialZugang> findByIdAndShop(String id, Shop shop);

    // Beim Loeschen eines Ladens (AdminController#deleteShop).
    void deleteByShop(Shop shop);
}
