package com.example.stemplekarte.security;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Laden am Personal-Token muss mitgeladen werden.
 *
 * <p>StaffToken.shop ist LAZY, der StaffTokenFilter laedt den Token ausserhalb
 * jeder Transaktion, und open-in-view ist aus. Was der Filter in den
 * SecurityContext legt, traegt also einen Proxy, dessen Sitzung sofort danach
 * zu ist: getId() beantwortet er selbst, jeder andere Zugriff wirft.
 *
 * <p>Das hat zweimal zugeschlagen. Die Geo-Pruefung fasst getLatitude() an -
 * ihr Fehler verschwindet still im catch, weshalb nie ein Vorfall
 * protokolliert wurde. Und die Standort-Pflicht fasst dasselbe Feld an, nur
 * ohne catch: damit waere jeder Scan mit einem Fehler abgebrochen.
 */
@DataJpaTest
class StaffTokenLadenTest {

    @Autowired private StaffTokenRepository tokens;
    @Autowired private ShopRepository shops;
    @Autowired private EntityManager em;

    private String legeAn() {
        Shop shop = shops.save(Shop.create("test@localhost.test", "hash", "Testladen", 5));
        StaffToken token = tokens.save(StaffToken.create(shop, "Kasse 1"));
        // Sitzung leeren: ab hier verhaelt sich der Zugriff wie im Filter,
        // der den Token frisch aus der Datenbank holt.
        em.flush();
        em.clear();
        return token.getToken();
    }

    @Test
    void findByIdLaesstDenLadenAlsProxyZurueck() {
        StaffToken geladen = tokens.findById(legeAn()).orElseThrow();

        assertThat(Hibernate.isInitialized(geladen.getShop()))
                .as("so war es - und deshalb flog jeder Zugriff auf ein Shop-Feld")
                .isFalse();
    }

    @Test
    void findWithShopByTokenLaedtDenLadenMit() {
        StaffToken geladen = tokens.findWithShopByToken(legeAn()).orElseThrow();

        assertThat(Hibernate.isInitialized(geladen.getShop())).isTrue();
    }

    /**
     * Der Fall, auf den es ankommt: ausserhalb jeder Transaktion ein Feld des
     * Ladens lesen, so wie Standort-Pflicht und Geo-Pruefung es tun.
     */
    @Test
    void felderDesLadensSindOhneSitzungLesbar() {
        StaffToken geladen = tokens.findWithShopByToken(legeAn()).orElseThrow();
        em.clear();

        assertThat(geladen.getShop().getName()).isEqualTo("Testladen");
        assertThat(geladen.getShop().getCreatedAt()).isNotNull();
        // Latitude ist hier null - entscheidend ist, dass der Zugriff nicht wirft.
        assertThat(geladen.getShop().getLatitude()).isNull();
    }
}
