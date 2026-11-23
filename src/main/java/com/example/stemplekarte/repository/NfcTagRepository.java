package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.NfcTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NfcTagRepository extends JpaRepository<NfcTag, String> {

    /** Der Kunde tippt an: nur das Token aus dem Link ist bekannt. */
    Optional<NfcTag> findByToken(String token);

    List<NfcTag> findByCardIdOrderByCreatedAtAsc(String cardId);

    long countByCardId(String cardId);

    Optional<NfcTag> findByIdAndShopId(String id, String shopId);

    // Beim Loeschen eines Ladens (AdminController#deleteShop).
    void deleteByShopId(String shopId);
}
