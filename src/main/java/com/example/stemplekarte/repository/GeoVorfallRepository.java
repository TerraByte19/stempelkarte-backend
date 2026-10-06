package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.GeoVorfall;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface GeoVorfallRepository extends JpaRepository<GeoVorfall, String> {

    List<GeoVorfall> findTop100ByOrderByPassiertAmDesc();

    List<GeoVorfall> findTop100ByShopIdOrderByPassiertAmDesc(String shopId);

    long countByPassiertAmAfter(Instant zeitpunkt);
}
