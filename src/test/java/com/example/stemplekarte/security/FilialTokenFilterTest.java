package com.example.stemplekarte.security;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.FilialZugangRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ein Unterkonto ist ROLE_FILIALE und nie ROLE_SHOP. Sperre, Passwortwechsel,
 * Tarifwechsel, gesperrter Laden und ausgeschaltete Filiale wirken sofort.
 */
class FilialTokenFilterTest {

    private static final String SECRET = "test-secret-mindestens-32-zeichen-lang-0123456789";

    private JwtService jwt;
    private ShopRepository shops;
    private FilialZugangRepository zugaenge;
    private ShopOrtRepository orte;
    private Shop kette;
    private FilialZugang zugang;

    @BeforeEach
    void aufbau() {
        jwt = new JwtService(SECRET);
        shops = mock(ShopRepository.class);
        zugaenge = mock(FilialZugangRepository.class);
        orte = mock(ShopOrtRepository.class);
        kette = Shop.create("z@kette.de", "HASH", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        zugang = FilialZugang.create(kette, "backbaron-mitte", "HASH", null);
        when(zugaenge.findWithShopById(zugang.getId())).thenReturn(Optional.of(zugang));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void aufraeumen() {
        SecurityContextHolder.clearContext();
    }

    private String token() {
        return jwt.generateFilialToken(zugang.getId(), kette.getId(), zugang.getTokenVersion());
    }

    private Authentication anmelden(String token) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        new JwtAuthFilter(jwt, shops, zugaenge, orte)
                .doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void unterkonto_istFilialeUndNieShop() throws Exception {
        Authentication auth = anmelden(token());
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_FILIALE");
        assertThat(auth.getPrincipal()).isInstanceOf(JwtAuthFilter.FilialPrincipal.class);
        JwtAuthFilter.FilialPrincipal p = (JwtAuthFilter.FilialPrincipal) auth.getPrincipal();
        assertThat(p.zugang()).isSameAs(zugang);
        assertThat(p.shop()).isSameAs(kette);
    }

    @Test
    void gesperrt_bleibtDraussen() throws Exception {
        String alt = token();
        zugang.sperre(true);
        assertThat(anmelden(alt)).isNull();
        assertThat(anmelden(token())).isNull();
    }

    @Test
    void altesTokenNachPasswortwechsel_giltNicht() throws Exception {
        String alt = token();
        zugang.neuesPasswort("NEU");
        assertThat(anmelden(alt)).isNull();
        assertThat(anmelden(token())).isNotNull();
    }

    @Test
    void ohneTarifKette_giltNicht() throws Exception {
        kette.setTarif(Tarif.PRO);
        assertThat(anmelden(token())).isNull();
    }

    @Test
    void gesperrterLaden_giltNicht() throws Exception {
        kette.setActive(false);
        assertThat(anmelden(token())).isNull();
    }

    @Test
    void ausgeschalteteFiliale_giltNicht() throws Exception {
        ShopOrt nord = ShopOrt.create(kette, "Nord", 52.5, 13.4);
        when(orte.findById(nord.getId())).thenReturn(Optional.of(nord));
        zugang = FilialZugang.create(kette, "backbaron-nord", "HASH", nord.getId());
        when(zugaenge.findWithShopById(zugang.getId())).thenReturn(Optional.of(zugang));

        assertThat(anmelden(token())).isNotNull();
        nord.setAktiv(false);
        assertThat(anmelden(token())).isNull();
    }

    @Test
    void geloeschterZugang_giltNicht() throws Exception {
        String t = token();
        when(zugaenge.findWithShopById(zugang.getId())).thenReturn(Optional.empty());
        assertThat(anmelden(t)).isNull();
    }

    @Test
    void alterKonstruktor_kenntKeineUnterkonten() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token());
        new JwtAuthFilter(jwt, shops).doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
