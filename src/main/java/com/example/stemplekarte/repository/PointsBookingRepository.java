package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.PointsBooking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PointsBookingRepository extends JpaRepository<PointsBooking, String> {

    // Die letzten Buchungen einer Karte, neueste zuerst. Zehn reichen fuer
    // den Scanner: dort wird nur die juengste zum Zuruecknehmen angeboten,
    // der Rest dient der Nachschau an der Theke.
    List<PointsBooking> findTop10ByCustomerCardIdOrderByCreatedAtDesc(String customerCardId);

    // Fuer die Statistik: alle Buchungen eines Ladens ab einem Zeitpunkt.
    List<PointsBooking> findByShopIdAndCreatedAtAfterOrderByCreatedAtAsc(
            String shopId, Instant after);
}
