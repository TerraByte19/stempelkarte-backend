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

    // Die juengsten Scans fuer "Zuletzt passiert" im Dashboard.
    List<ScanLog> findTop15ByShopIdOrderByScannedAtDesc(String shopId);

    /** Letzter Scan einer Kundenkarte in diesem Laden (Sperrzeit am Tablet, KioskService). */
    java.util.Optional<ScanLog> findTopByShopIdAndCustomerIdAndCardIdOrderByScannedAtDesc(
            String shopId, String customerId, String cardId);

    // Wird der QR-Code dieser Karte benutzt? Scans seit <after>.
    long countByCardIdAndScannedAtAfter(String cardId, Instant after);

    // Wie viel hat ein Mitarbeiter-Zugang (nach Name) seit <after> gestempelt.
    long countByShopIdAndStaffLabelAndScannedAtAfter(String shopId, String staffLabel, Instant after);

    // Alle Scans eines Shops in einem Zeitfenster (Tages-Detail: Stunden-
    // verteilung fuer EINEN Tag).
    List<ScanLog> findByShopIdAndScannedAtBetweenOrderByScannedAtAsc(String shopId, Instant from, Instant to);

    // Besuche je Kundenkarte fuer die Kundengruppen von Punktekarten (die
    // zaehlen keine Stempel). Eine Abfrage je Laden: [customerId, cardId, anzahl].
    @Query("select sl.customerId, sl.cardId, count(sl) from ScanLog sl "
            + "where sl.shopId = :shopId group by sl.customerId, sl.cardId")
    List<Object[]> zaehleJeKundenkarte(@Param("shopId") String shopId);

    // Dasselbe fuer EINE Karte (nach einer Korrektur in der Kundenliste).
    long countByCardIdAndCustomerId(String cardId, String customerId);

    // Wie viele UNTERSCHIEDLICHE Kunden haben seit <after> mindestens einmal
    // gescannt. Ehrliche "aktiv"-Kennzahl - der CustomerCard.updatedAt-Weg
    // zaehlt auch frisch angelegte Karten und Admin-Resets als "aktiv".
    @Query("select count(distinct sl.customerId) from ScanLog sl "
            + "where sl.shopId = :shopId and sl.scannedAt > :after")
    long countDistinctCustomersSince(@Param("shopId") String shopId, @Param("after") Instant after);
}