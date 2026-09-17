package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import com.example.stemplekarte.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ShopService {

    private final ShopRepository shopRepo;
    private final StaffTokenRepository staffTokenRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public ShopService(ShopRepository shopRepo, StaffTokenRepository staffTokenRepo,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.shopRepo = shopRepo;
        this.staffTokenRepo = staffTokenRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public Shop register(String email, String password, String name, int maxTokens) {
        if (shopRepo.existsByEmail(email.toLowerCase().trim())) {
            throw new IllegalArgumentException("E-Mail wird bereits verwendet");
        }
        Shop shop = Shop.create(
                email.toLowerCase().trim(),
                passwordEncoder.encode(password),
                name,
                maxTokens
        );
        Shop saved = shopRepo.save(shop);
        staffTokenRepo.save(StaffToken.create(saved, "Standard-Mitarbeiter"));
        return saved;
    }

    public String login(String email, String password) {
        Shop shop = shopRepo.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new IllegalArgumentException("E-Mail oder Passwort falsch"));
        if (!shop.isActive()) {
            throw new IllegalArgumentException("Account ist deaktiviert");
        }
        if (!passwordEncoder.matches(password, shop.getPasswordHash())) {
            throw new IllegalArgumentException("E-Mail oder Passwort falsch");
        }
        return jwtService.generateToken(shop.getId(), shop.getEmail());
    }

    public Shop getById(String id) {
        return shopRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
    }

    public Shop getByEmail(String email) {
        return shopRepo.findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
    }

    @Transactional
    public Shop updateProfile(String shopId, String name, String logoUrl,
                              String colorBackground, String colorForeground, String colorLabel) {
        Shop shop = getById(shopId);
        shop.update(name, logoUrl, colorBackground, colorForeground, colorLabel);
        return shopRepo.save(shop);
    }

    /**
     * Sperrbildschirm-Erinnerung ein-/ausschalten und Ladenkoordinaten setzen.
     *
     * Wirkt nicht sofort auf schon installierte Waellet-Karten: die Ortsangabe
     * steckt IM Pass, kommt also erst mit dem naechsten Stempel aufs Handy.
     * Genau dann ist sie auch relevant - der Eintrag entsteht ohnehin nur bei
     * voller Karte.
     */
    @Transactional
    public Shop updateLockScreen(String shopId, Boolean enabled,
                                 Double latitude, Double longitude) {
        if (latitude != null && (latitude < -90 || latitude > 90)) {
            throw new IllegalArgumentException("Breitengrad muss zwischen -90 und 90 liegen");
        }
        if (longitude != null && (longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("Laengengrad muss zwischen -180 und 180 liegen");
        }
        Shop shop = getById(shopId);
        if (Boolean.TRUE.equals(enabled)
                && (latitude == null && shop.getLatitude() == null)) {
            throw new IllegalArgumentException(
                    "Ohne Koordinaten kann die Erinnerung nicht eingeschaltet werden");
        }
        shop.updateLockScreen(enabled, latitude, longitude);
        return shopRepo.save(shop);
    }

    /** Eigener Text fuer die Sperrbildschirm-Erinnerung. Leer = Standardtext. */
    @Transactional
    public Shop updateLockScreenTexts(String shopId, String textFull, String textProgress) {
        if (textFull != null && textFull.length() > 120) {
            throw new IllegalArgumentException("Text darf hoechstens 120 Zeichen haben");
        }
        if (textProgress != null && textProgress.length() > 120) {
            throw new IllegalArgumentException("Text darf hoechstens 120 Zeichen haben");
        }
        Shop shop = getById(shopId);
        shop.updateLockScreenTexts(textFull, textProgress);
        return shopRepo.save(shop);
    }

    @Transactional
    public Shop updateExcludeSundayFromStats(String shopId, boolean enabled) {
        Shop shop = getById(shopId);
        shop.setExcludeSundayFromStats(enabled);
        return shopRepo.save(shop);
    }

    @Transactional
    public StaffToken createStaffToken(String shopId, String label) {
        Shop shop = getById(shopId);
        List<StaffToken> existing = staffTokenRepo.findByShop(shop);
        if (existing.size() >= shop.getMaxTokens()) {
            throw new IllegalArgumentException(
                    "Maximale Anzahl an Staff-Tokens erreicht (" + shop.getMaxTokens() + ")"
            );
        }
        return staffTokenRepo.save(StaffToken.create(shop, label));
    }

    public List<StaffToken> getStaffTokens(String shopId) {
        Shop shop = getById(shopId);
        return staffTokenRepo.findByShop(shop);
    }
}