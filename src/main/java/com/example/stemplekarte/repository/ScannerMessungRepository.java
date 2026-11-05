package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.ScannerMessung;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ScannerMessungRepository extends JpaRepository<ScannerMessung, String> {

    /** Begrenzt: mehr als 20000 Zeilen braucht die Zusammenfassung nicht, und mehr soll nicht in den Speicher. */
    List<ScannerMessung> findTop20000ByErstelltAmAfterOrderByErstelltAmDesc(Instant after);

    long countByShopIdAndErstelltAmAfter(String shopId, Instant after);
}
