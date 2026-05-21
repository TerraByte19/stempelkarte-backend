package com.example.stemplekarte.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private static final long EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000L; // 7 Tage
    private static final long ADMIN_EXPIRATION_MS = 4 * 60 * 60 * 1000L; // 4 Stunden

    public JwtService(@Value("${stempelkarte.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(String shopId, String email) {
        return Jwts.builder()
                .subject(shopId)
                .claim("email", email)
                .claim("role", "SHOP")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(key)
                .compact();
    }

    public String generateAdminToken() {
        return Jwts.builder()
                .subject("admin")
                .claim("role", "ADMIN")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ADMIN_EXPIRATION_MS))
                .signWith(key)
                .compact();
    }

    public String extractShopId(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isAdmin(String token) {
        try {
            Claims claims = parseClaims(token);
            return "ADMIN".equals(claims.get("role", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}