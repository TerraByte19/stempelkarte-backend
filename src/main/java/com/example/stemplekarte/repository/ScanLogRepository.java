package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.ScanLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ScanLogRepository extends JpaRepository<ScanLog, String> {

    // Alle Scans eines Shops ab einem Zeitpunkt (für Verlaufs-Statistik),
    // älteste zuerst.
    List<ScanLog> findByShopIdAndScannedAtAfterOrderByScannedAtAsc(String shopId, Instant after);
}