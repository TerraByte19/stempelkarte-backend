package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.ScanLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
    // Letzter Stempel dieses Kunden auf dieser Karte. Grundlage der
    // Abklingzeit beim NFC-Tippen: ohne sie gibt ein zweites Antippen
    // sofort den naechsten Stempel.
    Optional<ScanLog> findFirstByCustomerIdAndCardIdOrderByScannedAtDesc(String customerId, String cardId);

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

    // ── Statistik je Filiale (FilialStatistikService) ───────────────────
    // ortId null = Hauptstandort. Zeitfenster: von (einschliesslich) bis (ausschliesslich).

    /**
     * Je Filiale: [ortId, scans, stempel, eingeloest, kunden].
     * stempel ohne die Durchlaeufe, in denen eingeloest statt gestempelt wurde
     * (stampsAdded zaehlt sie mit).
     */
    @Query("select sl.ortId, count(sl), "
            + "sum(case when sl.stampsAdded > coalesce(sl.eingeloest, 0) "
            + "         then sl.stampsAdded - coalesce(sl.eingeloest, 0) else 0 end), "
            + "sum(coalesce(sl.eingeloest, 0)), count(distinct sl.customerId) "
            + "from ScanLog sl where sl.shopId = :shopId "
            + "and sl.scannedAt >= :von and sl.scannedAt < :bis group by sl.ortId")
    List<Object[]> zahlenJeFiliale(@Param("shopId") String shopId,
                                   @Param("von") Instant von, @Param("bis") Instant bis);

    /**
     * Neue Kunden je Filiale: [ortId, anzahl]. Neu ist, wessen allererster
     * Scan auf dieser Karte im Zeitfenster liegt; er zaehlt in der Filiale
     * dieses ersten Scans.
     */
    @Query("select sl.ortId, count(distinct sl.customerId) from ScanLog sl "
            + "where sl.shopId = :shopId and sl.customerId is not null "
            + "and sl.scannedAt >= :von and sl.scannedAt < :bis "
            + "and sl.scannedAt = (select min(e.scannedAt) from ScanLog e "
            + "    where e.shopId = :shopId and e.customerId = sl.customerId and e.cardId = sl.cardId) "
            + "group by sl.ortId")
    List<Object[]> neueKundenJeFiliale(@Param("shopId") String shopId,
                                       @Param("von") Instant von, @Param("bis") Instant bis);

    /** Unterschiedliche Kunden im Zeitfenster ueber alle Filialen (fuer die Summenzeile). */
    @Query("select count(distinct sl.customerId) from ScanLog sl where sl.shopId = :shopId "
            + "and sl.scannedAt >= :von and sl.scannedAt < :bis")
    long kundenImZeitraum(@Param("shopId") String shopId,
                          @Param("von") Instant von, @Param("bis") Instant bis);

    /**
     * Besuche EINES Kunden auf einer Karte je Filiale: [ortId, anzahl, letzter].
     * Fuer die Orte im Apple-Pass von Ketten (wallet.KettenOrte).
     */
    @Query("select sl.ortId, count(sl), max(sl.scannedAt) from ScanLog sl "
            + "where sl.shopId = :shopId and sl.customerId = :customerId and sl.cardId = :cardId "
            + "group by sl.ortId")
    List<Object[]> besucheJeFiliale(@Param("shopId") String shopId,
                                    @Param("customerId") String customerId, @Param("cardId") String cardId);
}
