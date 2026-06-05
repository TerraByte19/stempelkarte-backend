package com.example.stemplekarte.security;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ShopRepository shopRepo;

    public JwtAuthFilter(JwtService jwtService, ShopRepository shopRepo) {
        this.jwtService = jwtService;
        this.shopRepo = shopRepo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();

            if (jwtService.isValid(token)) {
                // ── Admin-Token ───────────────────────────────────────────
                if (jwtService.isAdmin(token)) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            "admin",
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else {
                    // ── Shop-Token ────────────────────────────────────────
                    String shopId = jwtService.extractShopId(token);
                    shopRepo.findById(shopId).ifPresent(shop -> {
                        var auth = new UsernamePasswordAuthenticationToken(
                                new ShopPrincipal(shop),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_SHOP"))
                        );
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
                }
            }
        }
        chain.doFilter(req, resp);
    }

    public record ShopPrincipal(Shop shop) {}
}