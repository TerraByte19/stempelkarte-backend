package com.example.stemplekarte.security;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nur ein Token mit Rolle SHOP ist der Inhaber. Ein Token mit anderer oder
 * ohne Rolle darf nie als Inhaber durchgehen - auch wenn sein Subject eine
 * Laden-ID ist.
 */
class RollenFilterTest {

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

    private String tokenMitRolle(String rolle) {
        var b = Jwts.builder()
                .subject(shop.getId())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (rolle != null) b.claim("role", rolle);
        return b.signWith(Keys.hmacShaKeyFor(SECRET.getBytes())).compact();
    }

    @Test
    void ladenToken_bleibtInhaber() throws Exception {
        Authentication auth = anmelden(jwt.generateToken(shop.getId(), shop.getEmail()));
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_SHOP");
    }

    @Test
    void tokenOhneRolle_istNiemand() throws Exception {
        assertThat(anmelden(tokenMitRolle(null))).isNull();
    }

    @Test
    void tokenMitUnbekannterRolle_istNiemand() throws Exception {
        assertThat(anmelden(tokenMitRolle("IRGENDWAS"))).isNull();
    }

    @Test
    void filialToken_mitLadenIdAlsSubject_istKeinInhaber() throws Exception {
        // Der gefaehrliche Fall: Subject = Laden-ID, Rolle = FILIALE.
        Authentication auth = anmelden(tokenMitRolle("FILIALE"));
        assertThat(auth).isNull();
    }

    @Test
    void filialToken_traegtVersion() {
        String t = jwt.generateFilialToken("FZ-1", shop.getId(), 4);
        assertThat(jwt.extractRole(t)).isEqualTo("FILIALE");
        assertThat(jwt.extractShopId(t)).isEqualTo("FZ-1");
        assertThat(jwt.extractVersion(t)).isEqualTo(4);
    }
}
