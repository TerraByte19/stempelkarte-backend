package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Laden am Unterkonto muss mitgeladen sein: der Filter liest ihn
 * ausserhalb jeder Transaktion (gleiche Falle wie StaffTokenLadenTest).
 */
@DataJpaTest
class FilialZugangLadenTest {

    @Autowired private FilialZugangRepository zugaenge;
    @Autowired private ShopRepository shops;
    @Autowired private EntityManager em;

    @Test
    void ladenIstMitgeladen_perIdUndPerBenutzername() {
        Shop shop = shops.save(Shop.create("kette@localhost.test", "hash", "Kette", 5));
        FilialZugang z = zugaenge.save(FilialZugang.create(shop, "kette-mitte", "hash", null));
        em.flush();
        em.clear();

        FilialZugang perId = zugaenge.findWithShopById(z.getId()).orElseThrow();
        assertThat(Hibernate.isInitialized(perId.getShop())).isTrue();
        em.clear();

        FilialZugang perName = zugaenge.findWithShopByBenutzername("kette-mitte").orElseThrow();
        assertThat(Hibernate.isInitialized(perName.getShop())).isTrue();
        assertThat(zugaenge.existsByBenutzername("kette-mitte")).isTrue();
        assertThat(zugaenge.existsByBenutzername("gibt-es-nicht")).isFalse();
    }
}
