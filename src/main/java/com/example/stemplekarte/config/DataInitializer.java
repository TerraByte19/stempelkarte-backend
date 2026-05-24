package com.example.stemplekarte.config;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.service.ShopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final ShopRepository shopRepo;
    private final ShopService shopService;

    public DataInitializer(ShopRepository shopRepo, ShopService shopService) {
        this.shopRepo = shopRepo;
        this.shopService = shopService;
    }

    @Override
    public void run(String... args) {
        if (shopRepo.count() == 0) {
            Shop demo = shopService.register(
                    "demo@cafeespresso.de",
                    "demo1234",
                    "Cafe Espresso Demo",
                    5
            );
            String token = shopService.login("demo@cafeespresso.de", "demo1234");
            log.info("==========================================================");
            log.info("Demo Shop erstellt!");
            log.info("E-Mail:    demo@cafeespresso.de");
            log.info("Passwort:  demo1234");
            log.info("JWT Token: {}", token);
            log.info("==========================================================");
        }
    }
}