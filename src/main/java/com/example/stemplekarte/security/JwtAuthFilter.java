package com.example.stemplekarte.security;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.FilialZugangRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
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
    // Beide null = Unterkonten sind aus (alte Aufrufer, Tests).
    private final FilialZugangRepository filialRepo;
    private final ShopOrtRepository ortRepo;

    public JwtAuthFilter(JwtService jwtService, ShopRepository shopRepo) {
        this(jwtService, shopRepo, null, null);
    }

    public JwtAuthFilter(JwtService jwtService, ShopRepository shopRepo,
                         FilialZugangRepository filialRepo, ShopOrtRepository ortRepo) {
        this.jwtService = jwtService;
        this.shopRepo = shopRepo;
        this.filialRepo = filialRepo;
        this.ortRepo = ortRepo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();

            if (jwtService.isValid(token)) {
                // Die Rolle im Token entscheidet. Ohne oder mit unbekannter
                // Rolle bleibt die Anfrage anonym - nie "im Zweifel Inhaber".
                String rolle = jwtService.extractRole(token);
                if ("ADMIN".equals(rolle)) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            "admin",
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else if ("SHOP".equals(rolle)) {
                    // filter(isActive): ein vom Admin gesperrter Laden fliegt
                    // sofort raus, nicht erst wenn sein Token ablaeuft.
                    String shopId = jwtService.extractShopId(token);
                    shopRepo.findById(shopId).filter(com.example.stemplekarte.model.Shop::isActive).ifPresent(shop -> {
                        var auth = new UsernamePasswordAuthenticationToken(
                                new ShopPrincipal(shop),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_SHOP"))
                        );
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
                } else if ("FILIALE".equals(rolle)) {
                    filialeAnmelden(token);
                }
            }
        }
        chain.doFilter(req, resp);
    }

    /**
     * Unterkonto einer Filiale. Jede Bedingung wird bei JEDER Anfrage neu
     * geprueft: Sperre, Passwortwechsel (Version), Tarifwechsel, gesperrter
     * Laden und ausgeschaltete Filiale wirken sofort, nicht erst wenn der
     * Token ablaeuft.
     */
    private void filialeAnmelden(String token) {
        if (filialRepo == null || ortRepo == null) return;
        Integer version = jwtService.extractVersion(token);
        if (version == null) return;
        filialRepo.findWithShopById(jwtService.extractShopId(token))
                .filter(z -> !z.isGesperrt())
                .filter(z -> z.getTokenVersion() == version)
                .filter(z -> z.getShop().isActive())
                .filter(z -> z.getShop().getTarif() == Tarif.KETTE)
                .filter(this::ortAktiv)
                .ifPresent(z -> {
                    var auth = new UsernamePasswordAuthenticationToken(
                            new FilialPrincipal(z, z.getShop()),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_FILIALE"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
    }

    /**
     * Hauptstandort: an, solange er als Filiale zaehlt (Shop.hauptIstFiliale).
     * Eine geloeschte Filiale ist aus. Dieselbe Regel wie FilialZugangService.ortAktiv.
     */
    private boolean ortAktiv(FilialZugang z) {
        if (z.getOrtId() == null) return false;   // kein Login am Hauptstandort
        return ortRepo.findById(z.getOrtId()).map(ShopOrt::isAktiv).orElse(false);
    }

    public record ShopPrincipal(Shop shop) {}

    public record FilialPrincipal(FilialZugang zugang, Shop shop) {}
}