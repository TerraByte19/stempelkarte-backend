package com.example.stemplekarte.service;

import com.example.stemplekarte.model.ApiKey;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ApiKeyRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * API-Schluessel eines Ladens: anlegen (Schluessel einmal zeigen), listen,
 * widerrufen. Gespeichert wird nur der Hash.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    public static final int MAX_JE_LADEN = 5;
    static final String PRAEFIX = "sk_";
    static final int LAENGE = 40;
    private static final String ZEICHEN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    public static final String NICHT_FREIGESCHALTET = "Die Schnittstelle ist für diesen Laden nicht freigeschaltet";

    private final ApiKeyRepository repo;
    private final ShopRepository shopRepo;
    private final SecureRandom zufall = new SecureRandom();

    public ApiKeyService(ApiKeyRepository repo, ShopRepository shopRepo) {
        this.repo = repo;
        this.shopRepo = shopRepo;
    }

    /** key nur hier, nur einmal. */
    public record Neu(String id, String label, String key) {}
    public record Eintrag(String id, String label, String prefix, String erstellt, String zuletzt, boolean aktiv) {}

    public static String hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String neuerKey() {
        StringBuilder sb = new StringBuilder(PRAEFIX);
        for (int i = 0; i < LAENGE; i++) sb.append(ZEICHEN.charAt(zufall.nextInt(ZEICHEN.length())));
        return sb.toString();
    }

    /** IllegalStateException (409) ohne Freischaltung, IllegalArgumentException (400) bei Label oder Anzahl. */
    @Transactional
    public Neu anlegen(String shopId, String labelRoh) {
        Shop shop = shopRepo.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
        TarifPruefung.pruefe(shop, com.example.stemplekarte.model.Tarif.Funktion.API);
        if (!shop.isApiFreigeschaltet()) throw new IllegalStateException(NICHT_FREIGESCHALTET);
        String label = labelRoh == null ? "" : labelRoh.replaceAll("\\p{Cntrl}", " ").trim();
        if (label.isEmpty()) label = "Schlüssel";
        if (label.length() > ApiKey.LABEL_MAX) {
            throw new IllegalArgumentException("Der Name darf höchstens " + ApiKey.LABEL_MAX + " Zeichen haben");
        }
        if (repo.countByShopAndRevokedAtIsNull(shop) >= MAX_JE_LADEN) {
            throw new IllegalArgumentException("Höchstens " + MAX_JE_LADEN + " Schlüssel. Erst einen widerrufen.");
        }
        String key = neuerKey();
        ApiKey k = repo.save(ApiKey.neu(shop, label, key.substring(0, 10), hash(key), Instant.now()));
        log.info("[API] Laden {}: Schluessel {} ({}) angelegt", shopId, k.getId(), label);
        return new Neu(k.getId(), label, key);
    }

    public List<Eintrag> liste(Shop shop) {
        return repo.findByShopOrderByCreatedAtDesc(shop).stream()
                .map(k -> new Eintrag(k.getId(), k.getLabel(), k.getPrefix() + "…",
                        k.getCreatedAt().toString(),
                        k.getLastUsedAt() != null ? k.getLastUsedAt().toString() : null,
                        k.istAktiv()))
                .toList();
    }

    @Transactional
    public void widerrufen(Shop shop, String id) {
        ApiKey k = repo.findByIdAndShop(id, shop)
                .orElseThrow(() -> new NoSuchElementException("Schlüssel nicht gefunden"));
        if (!k.istAktiv()) return;
        k.widerrufen(Instant.now());
        repo.save(k);
        log.info("[API] Laden {}: Schluessel {} widerrufen", shop.getId(), id);
    }
}
