package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.KorrekturLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KorrekturLogRepository extends JpaRepository<KorrekturLog, String> {

    /** Die juengsten Korrekturen eines Ladens - fuer das Hauptkonto. */
    List<KorrekturLog> findTop100ByShopIdOrderByErstelltAmDesc(String shopId);

    // Beim Loeschen eines Ladens (AdminController#deleteShop).
    void deleteByShopId(String shopId);
}
