package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.ScanLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ScanLogRepository extends JpaRepository<ScanLog, String> {

    // Alle Scans eines Shops ab einem Zeitpunkt (für Verlaufs-Statistik),
    // älteste zuerst.
    List<ScanLog> findByShopIdAndScannedAtAfterOrderByScannedAtAsc(String shopId, Instant after);

    // Alle Scans eines Shops, neueste zuerst (Debug/Support-Auswertung).
    List<ScanLog> findByShopIdOrderByScannedAtDesc(String shopId);

    // Wie viele UNTERSCHIEDLICHE Kunden haben seit <after> mindestens einmal
    // gescannt. Ehrliche "aktiv"-Kennzahl - der CustomerCard.updatedAt-Weg
    // zaehlt auch frisch angelegte Karten und Admin-Resets als "aktiv".
    @Query("select count(distinct sl.customerId) from ScanLog sl "
            + "where sl.shopId = :shopId and sl.scannedAt > :after")
    long countDistinctCustomersSince(@Param("shopId") String shopId, @Param("after") Instant after);
}