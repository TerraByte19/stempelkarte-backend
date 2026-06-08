package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DesignMigration {

    private static final Logger log = LoggerFactory.getLogger(DesignMigration.class);
    private final ShopRepository shopRepo;

    public DesignMigration(ShopRepository shopRepo) {
        this.shopRepo = shopRepo;
    }

    @PostConstruct
    public void fixWalletStyle() {
        int fixed = 0;
        for (Shop shop : shopRepo.findAll()) {
            if (!"number".equalsIgnoreCase(shop.getWalletStyle())) {
                log.info("Migration: Shop {} walletStyle '{}' -> 'number'",
                        shop.getId(), shop.getWalletStyle());
                shop.updateDesign("number", null, null, null, null);
                shopRepo.save(shop);
                fixed++;
            }
        }
        log.info("Design-Migration fertig. Korrigiert: {}", fixed);
    }
}