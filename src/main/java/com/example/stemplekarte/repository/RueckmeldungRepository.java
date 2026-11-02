package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Rueckmeldung;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RueckmeldungRepository extends JpaRepository<Rueckmeldung, String> {

    List<Rueckmeldung> findTop50ByShopOrderByEingegangenAmDesc(Shop shop);

    List<Rueckmeldung> findByShopAndEingegangenAmAfter(Shop shop, Instant seit);

    long countByShopAndGelesenFalse(Shop shop);

    boolean existsByCustomerCardIdAndEingegangenAmAfter(String customerCardId, Instant seit);

    Optional<Rueckmeldung> findByIdAndShop(String id, Shop shop);

    void deleteByShop(Shop shop);
}
