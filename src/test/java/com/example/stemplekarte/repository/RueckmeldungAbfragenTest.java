package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Rueckmeldung;
import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Rueckmeldungs-Abfragen gegen eine echte Datenbank: Reihenfolge, Fenster, nur eigener Laden. */
@DataJpaTest
class RueckmeldungAbfragenTest {

    @Autowired private ShopRepository shops;
    @Autowired private RueckmeldungRepository rueckmeldungen;

    private final Instant jetzt = Instant.parse("2026-11-02T12:00:00Z");

    private Shop laden(String email) {
        return shops.save(Shop.create(email, "hash", "Laden " + email, 5));
    }

    @Test
    void neuesteZuerst_fensterUndZaehler_nurEigenerLaden() {
        Shop a = laden("a@laden.test");
        Shop b = laden("b@laden.test");
        Rueckmeldung alt = rueckmeldungen.save(Rueckmeldung.neu(a, "CC-1", 2, "alt", jetzt.minus(Duration.ofDays(40))));
        Rueckmeldung neu = rueckmeldungen.save(Rueckmeldung.neu(a, "CC-2", 5, "neu", jetzt.minus(Duration.ofHours(1))));
        neu.gelesen();
        rueckmeldungen.save(neu);
        rueckmeldungen.save(Rueckmeldung.neu(b, "CC-3", 1, "fremd", jetzt));

        List<Rueckmeldung> liste = rueckmeldungen.findTop50ByShopOrderByEingegangenAmDesc(a);
        assertThat(liste).extracting(Rueckmeldung::getId).containsExactly(neu.getId(), alt.getId());

        assertThat(rueckmeldungen.findByShopAndEingegangenAmAfter(a, jetzt.minus(Duration.ofDays(30))))
                .extracting(Rueckmeldung::getText).containsExactly("neu");
        assertThat(rueckmeldungen.countByShopAndGelesenFalse(a)).isEqualTo(1);
        assertThat(rueckmeldungen.findByIdAndShop(alt.getId(), b)).isEmpty();
        assertThat(rueckmeldungen.findByIdAndShop(alt.getId(), a)).isPresent();
    }

    @Test
    void einmalProKarteUndTag() {
        Shop a = laden("a@laden.test");
        rueckmeldungen.save(Rueckmeldung.neu(a, "CC-1", 4, null, jetzt.minus(Duration.ofHours(23))));
        assertThat(rueckmeldungen.existsByCustomerCardIdAndEingegangenAmAfter("CC-1", jetzt.minus(Duration.ofHours(24)))).isTrue();
        assertThat(rueckmeldungen.existsByCustomerCardIdAndEingegangenAmAfter("CC-1", jetzt.minus(Duration.ofHours(22)))).isFalse();
        assertThat(rueckmeldungen.existsByCustomerCardIdAndEingegangenAmAfter("CC-2", jetzt.minus(Duration.ofHours(24)))).isFalse();
    }

    @Test
    void loeschenMitLaden() {
        Shop a = laden("a@laden.test");
        Shop b = laden("b@laden.test");
        rueckmeldungen.save(Rueckmeldung.neu(a, "CC-1", 4, null, jetzt));
        rueckmeldungen.save(Rueckmeldung.neu(b, "CC-2", 4, null, jetzt));
        rueckmeldungen.deleteByShop(a);
        assertThat(rueckmeldungen.findTop50ByShopOrderByEingegangenAmDesc(a)).isEmpty();
        assertThat(rueckmeldungen.findTop50ByShopOrderByEingegangenAmDesc(b)).hasSize(1);
    }
}
