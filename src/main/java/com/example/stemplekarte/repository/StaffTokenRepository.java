package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StaffTokenRepository extends JpaRepository<StaffToken, String> {
    List<StaffToken> findByShop(Shop shop);

    /**
     * Laedt den Token MIT seinem Laden.
     *
     * <p>Der StaffTokenFilter laeuft ausserhalb jeder Transaktion und
     * open-in-view ist aus: ein LAZY geladener Laden waere danach ein Proxy
     * ohne Sitzung, und jeder Zugriff auf eines seiner Felder wuerfe. Genau
     * daran ist die Geo-Pruefung still gescheitert.
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "shop")
    java.util.Optional<StaffToken> findWithShopByToken(String token);
}