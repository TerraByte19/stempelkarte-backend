package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.WebhookEreignis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface WebhookEreignisRepository extends JpaRepository<WebhookEreignis, String> {

    // Faellige Ereignisse fuer den Laeufer, aelteste zuerst, in Haeppchen.
    List<WebhookEreignis> findTop50ByStatusAndNaechsterVersuchLessThanEqualOrderByNaechsterVersuchAsc(
            String status, Instant bis);

    List<WebhookEreignis> findTop20ByShopIdOrderByErstelltAmDesc(String shopId);

    long countByShopIdAndStatus(String shopId, String status);

    void deleteByShopId(String shopId);
}
