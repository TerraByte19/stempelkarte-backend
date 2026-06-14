package com.example.stemplekarte.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests für den Login-Brute-Force-Schutz.
 * Prüft: Sperre nach 10 Fehlversuchen, Reset bei Erfolg, IP-Trennung.
 */
class LoginAttemptServiceTest {

    @Test
    void neueIp_istNichtGesperrt() {
        LoginAttemptService service = new LoginAttemptService();
        assertThat(service.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void wenigeFehlversuche_sperrenNicht() {
        LoginAttemptService service = new LoginAttemptService();
        String ip = "1.2.3.4";
        // 9 Fehlversuche — noch unter dem Limit von 10
        for (int i = 0; i < 9; i++) {
            service.loginFailed(ip);
        }
        assertThat(service.isBlocked(ip)).isFalse();
    }

    @Test
    void zehnFehlversuche_sperrenDieIp() {
        LoginAttemptService service = new LoginAttemptService();
        String ip = "1.2.3.4";
        // 10 Fehlversuche — Limit erreicht
        for (int i = 0; i < 10; i++) {
            service.loginFailed(ip);
        }
        assertThat(service.isBlocked(ip)).isTrue();
    }

    @Test
    void erfolgreicherLogin_setztZaehlerZurueck() {
        LoginAttemptService service = new LoginAttemptService();
        String ip = "1.2.3.4";
        // 9 Fehlversuche, dann Erfolg
        for (int i = 0; i < 9; i++) {
            service.loginFailed(ip);
        }
        service.loginSucceeded(ip);
        // Nach Reset darf man wieder 9 Fehlversuche machen, ohne gesperrt zu sein
        for (int i = 0; i < 9; i++) {
            service.loginFailed(ip);
        }
        assertThat(service.isBlocked(ip)).isFalse();
    }

    @Test
    void verschiedeneIps_sindUnabhaengig() {
        LoginAttemptService service = new LoginAttemptService();
        String angreifer = "9.9.9.9";
        String echterNutzer = "1.1.1.1";

        // Angreifer-IP wird gesperrt
        for (int i = 0; i < 10; i++) {
            service.loginFailed(angreifer);
        }

        // Die IP des echten Nutzers ist davon NICHT betroffen
        assertThat(service.isBlocked(angreifer)).isTrue();
        assertThat(service.isBlocked(echterNutzer)).isFalse();
    }
}