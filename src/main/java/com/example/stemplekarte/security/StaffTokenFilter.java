package com.example.stemplekarte.security;

import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.StaffTokenRepository;
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

public class StaffTokenFilter extends OncePerRequestFilter {

    private final StaffTokenRepository repo;

    public StaffTokenFilter(StaffTokenRepository repo) {
        this.repo = repo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("X-Staff-Token");

        if (header != null && !header.isBlank()) {
            repo.findById(header.trim()).ifPresent(staff -> {
                var auth = new UsernamePasswordAuthenticationToken(
                        new StaffPrincipal(staff),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_STAFF"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }

        chain.doFilter(req, resp);
    }

    public record StaffPrincipal(StaffToken staff) {}
}