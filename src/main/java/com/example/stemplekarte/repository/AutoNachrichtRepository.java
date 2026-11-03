package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.AutoNachricht;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AutoNachrichtRepository extends JpaRepository<AutoNachricht, String> {

    List<AutoNachricht> findByShop(Shop shop);

    Optional<AutoNachricht> findByShopAndTyp(Shop shop, String typ);

    // Fuer den Laeufer: der Laden kommt gleich mit, open-in-view ist aus und
    // der Laeufer hat keine Transaktion.
    @Query("select a from AutoNachricht a join fetch a.shop where a.aktiv = true")
    List<AutoNachricht> findAktive();

    void deleteByShop(Shop shop);
}
