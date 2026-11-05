package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.ScannerMessung;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ScannerMessungRepository extends JpaRepository<ScannerMessung, String> {

    List<ScannerMessung> findByErstelltAmAfterOrderByErstelltAmDesc(Instant after);

    long countByShopIdAndErstelltAmAfter(String shopId, Instant after);
}
