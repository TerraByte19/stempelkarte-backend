package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Erinnert den Laden per Mail an das Ende der Gratis-Testphase:
 * einmal 3 Tage vorher ("endet bald") und einmal, wenn sie vorbei ist.
 *
 * Shop.testMailStufe merkt sich, was schon raus ist; nur eine Mail, die der
 * Mailserver angenommen hat, zaehlt. Verlaengert der Admin, setzt
 * Shop.setTestBis die Stufe zurueck. Verschickt wird nur tagsueber
 * (9 bis 20 Uhr Berlin), damit niemand nachts eine Mail bekommt.
 */
@Service
public class TestphaseErinnerungService {

    private static final Logger log = LoggerFactory.getLogger(TestphaseErinnerungService.class);
    static final int BALD_TAGE = 3;
    static final int STUFE_BALD = 1;
    static final int STUFE_VORBEI = 2;
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final ShopRepository shopRepo;
    private final EmailService emailService;
    private final String dashboardUrl;

    public TestphaseErinnerungService(ShopRepository shopRepo, EmailService emailService,
                                      @Value("${FRONTEND_URL:https://stempelkarte-frontend.vercel.app}") String frontendUrl) {
        this.shopRepo = shopRepo;
        this.emailService = emailService;
        // FRONTEND_URL kann eine Liste sein (CORS); die erste ist die App.
        String erste = frontendUrl.split(",")[0].trim();
        this.dashboardUrl = (erste.endsWith("/") ? erste.substring(0, erste.length() - 1) : erste) + "/dashboard";
    }

    @Scheduled(fixedDelay = 30 * 60_000, initialDelay = 6 * 60_000)
    public void lauf() {
        try {
            lauf(Instant.now());
        } catch (Exception e) {
            log.warn("[TEST] Erinnerung abgebrochen: {}", e.getMessage());
        }
    }

    /** Gibt die Zahl der verschickten Mails zurueck. */
    public int lauf(Instant jetzt) {
        int stunde = jetzt.atZone(BERLIN).getHour();
        if (stunde < 9 || stunde >= 20) return 0;
        int gesendet = 0;
        for (Shop shop : shopRepo.findByTestBisNotNull()) {
            if (!shop.isActive() || shop.getEmail() == null || shop.getEmail().isBlank()) continue;
            Integer tage = shop.testTageUebrig(jetzt);
            if (tage == null) continue;
            int stufe = shop.getTestMailStufe();
            boolean vorbei = tage == 0;
            int ziel = vorbei ? STUFE_VORBEI : (tage <= BALD_TAGE ? STUFE_BALD : 0);
            if (ziel == 0 || stufe >= ziel) continue;
            if (emailService.sendTestphaseMail(shop, shop.getTestBis(), vorbei, dashboardUrl)) {
                shop.setTestMailStufe(ziel);
                shopRepo.save(shop);
                gesendet++;
                log.info("[TEST] Erinnerung '{}' an Laden {}", vorbei ? "vorbei" : "bald", shop.getId());
            }
        }
        return gesendet;
    }
}
