package com.example.stemplekarte.security;

import com.example.stemplekarte.model.Shop;
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
 * Der Support-Token aus dem Admin-Panel meldet sich als genau dieser Laden an
 * (Rolle SHOP) und nie als Admin. Ein gesperrter Laden bleibt gesperrt.
 */
class SupportTokenFilterTest {

    private static final String SECRET = "test-secret-mindestens-32-zeichen-lang-0123456789";

    private JwtService jwt;
    private ShopRepository shops;
    private Shop shop;

    @BeforeEach
    void aufbau() {
        jwt = new JwtService(SECRET);
        shops = mock(ShopRepository.class);
        shop = Shop.create("a@laden.de", "HASH", "Laden A", 3);
        when(shops.findById(shop.getId())).thenReturn(Optional.of(shop));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void aufraeumen() {
        SecurityContextHolder.clearContext();
    }

    private Authentication anmelden(String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        new JwtAuthFilter(jwt, shops).doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void supportToken_istDerLadenMitRolleShop() throws Exception {
        Authentication auth = anmelden(jwt.generateSupportToken(shop.getId()));

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_SHOP");
        assertThat(auth.getPrincipal()).isInstanceOf(JwtAuthFilter.ShopPrincipal.class);
        assertThat(((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop()).isSameAs(shop);
    }

    @Test
    void supportToken_gesperrterLadenBleibtDraussen() throws Exception {
        shop.setActive(false);
        assertThat(anmelden(jwt.generateSupportToken(shop.getId()))).isNull();
    }

    @Test
    void supportToken_mitFremdemSchluesselGiltNicht() throws Exception {
        String fremd = new JwtService("ein-ganz-anderer-schluessel-mit-mehr-als-32-zeichen")
                .generateSupportToken(shop.getId());
        assertThat(anmelden(fremd)).isNull();
    }
}
