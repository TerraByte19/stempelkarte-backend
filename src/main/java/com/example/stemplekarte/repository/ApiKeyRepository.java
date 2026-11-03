package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.ApiKey;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {

    // Fuer den Filter: aktiver Schluessel MIT Laden, die Sitzung ist gleich zu.
    @Query("select k from ApiKey k join fetch k.shop where k.hash = :hash and k.revokedAt is null")
    Optional<ApiKey> findAktivByHash(String hash);

    List<ApiKey> findByShopOrderByCreatedAtDesc(Shop shop);

    long countByShopAndRevokedAtIsNull(Shop shop);

    Optional<ApiKey> findByIdAndShop(String id, Shop shop);

    void deleteByShop(Shop shop);
}
