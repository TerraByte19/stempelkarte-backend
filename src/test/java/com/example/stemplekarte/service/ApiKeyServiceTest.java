package com.example.stemplekarte.service;

import com.example.stemplekarte.model.ApiKey;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ApiKeyRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.ApiKeyFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API-Schluessel: nur mit Freischaltung, Schluessel einmal sichtbar, nur
 * der Hash gespeichert, hoechstens fuenf. Und der Filter: richtiger
 * Schluessel = ROLE_API, widerrufen oder Laden nicht frei = nichts.
 */
class ApiKeyServiceTest {

    private Shop shop;
    private ApiKeyRepository repo;
    private ApiKeyService dienst;

    @BeforeEach
    void setUp() {
        shop = Shop.create("a@b.de", "hash", "Café Mokka", 3);
        shop.setApiFreigeschaltet(true);
        repo = mock(ApiKeyRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ShopRepository shopRepo = mock(ShopRepository.class);
        when(shopRepo.findById(shop.getId())).thenReturn(Optional.of(shop));
        dienst = new ApiKeyService(repo, shopRepo);
    }

    @AfterEach
    void aufraeumen() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anlegen_schluesselEinmal_nurHashGespeichert() {
        ApiKeyService.Neu neu = dienst.anlegen(shop.getId(), " Kasse 1 ");
        assertThat(neu.key()).startsWith("sk_").hasSize(43);
        assertThat(neu.label()).isEqualTo("Kasse 1");

        ArgumentCaptor<ApiKey> gespeichert = ArgumentCaptor.forClass(ApiKey.class);
        verify(repo).save(gespeichert.capture());
        ApiKey k = gespeichert.getValue();
        assertThat(k.getHash()).isEqualTo(ApiKeyService.hash(neu.key())).hasSize(64);
        assertThat(k.getPrefix()).isEqualTo(neu.key().substring(0, 10));
        assertThat(k.istAktiv()).isTrue();
        // Der Schluessel selbst steht nirgends in der Zeile.
        assertThat(k.getHash()).doesNotContain(neu.key());
        assertThat(k.getPrefix()).isNotEqualTo(neu.key());
    }

    @Test
    void anlegen_ohneFreischaltung_zuViele_zuLangerName() {
        shop.setApiFreigeschaltet(false);
        assertThatThrownBy(() -> dienst.anlegen(shop.getId(), "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nicht freigeschaltet");
        shop.setApiFreigeschaltet(true);
        when(repo.countByShopAndRevokedAtIsNull(shop)).thenReturn(5L);
        assertThatThrownBy(() -> dienst.anlegen(shop.getId(), "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Höchstens 5");
        when(repo.countByShopAndRevokedAtIsNull(shop)).thenReturn(0L);
        assertThatThrownBy(() -> dienst.anlegen(shop.getId(), "x".repeat(41)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dienst.anlegen(shop.getId(), null).label()).isEqualTo("Schlüssel");
    }

    @Test
    void liste_zeigtNurPrefix_undWiderrufen() {
        ApiKey k = ApiKey.neu(shop, "Kasse", "sk_abcdefg", ApiKeyService.hash("sk_abcdefghij"), Instant.now());
        when(repo.findByShopOrderByCreatedAtDesc(shop)).thenReturn(List.of(k));
        List<ApiKeyService.Eintrag> l = dienst.liste(shop);
        assertThat(l).hasSize(1);
        assertThat(l.get(0).prefix()).isEqualTo("sk_abcdefg…");
        assertThat(l.get(0).aktiv()).isTrue();

        when(repo.findByIdAndShop(k.getId(), shop)).thenReturn(Optional.of(k));
        dienst.widerrufen(shop, k.getId());
        assertThat(k.istAktiv()).isFalse();
        assertThat(dienst.liste(shop).get(0).aktiv()).isFalse();
        assertThatThrownBy(() -> dienst.widerrufen(shop, "AK-FREMD"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // ── Filter ──────────────────────────────────────────────────────────

    private void filtern(String header) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/cards");
        if (header != null) req.addHeader(ApiKeyFilter.HEADER, header);
        new ApiKeyFilter(repo).doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    void filter_richtigerSchluessel_roleApi() throws Exception {
        String key = "sk_" + "a".repeat(40);
        ApiKey k = ApiKey.neu(shop, "Kasse", key.substring(0, 10), ApiKeyService.hash(key), Instant.now());
        when(repo.findAktivByHash(ApiKeyService.hash(key))).thenReturn(Optional.of(k));

        filtern(key);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_API");
        ApiKeyFilter.ApiPrincipal p = (ApiKeyFilter.ApiPrincipal) auth.getPrincipal();
        assertThat(p.shop()).isSameAs(shop);
        assertThat(p.label()).isEqualTo("Kasse");
        assertThat(k.getLastUsedAt()).isNotNull();
    }

    @Test
    void filter_falschOderNichtFrei_nichts() throws Exception {
        filtern(null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        when(repo.findAktivByHash(anyString())).thenReturn(Optional.empty());
        filtern("sk_falsch");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        String key = "sk_" + "b".repeat(40);
        ApiKey k = ApiKey.neu(shop, "Kasse", key.substring(0, 10), ApiKeyService.hash(key), Instant.now());
        when(repo.findAktivByHash(ApiKeyService.hash(key))).thenReturn(Optional.of(k));
        shop.setApiFreigeschaltet(false);
        filtern(key);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
