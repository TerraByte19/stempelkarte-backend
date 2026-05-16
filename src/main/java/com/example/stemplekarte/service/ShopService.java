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
    public Shop register(String email, String password, String name) {
        if (shopRepo.existsByEmail(email.toLowerCase().trim())) {
            throw new IllegalArgumentException("E-Mail wird bereits verwendet");
        }
        Shop shop = Shop.create(
                email.toLowerCase().trim(),
                passwordEncoder.encode(password),
                name
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

    @Transactional
    public StaffToken createStaffToken(String shopId, String label) {
        Shop shop = getById(shopId);
        return staffTokenRepo.save(StaffToken.create(shop, label));
    }

    public List<StaffToken> getStaffTokens(String shopId) {
        Shop shop = getById(shopId);
        return staffTokenRepo.findByShop(shop);
    }
}