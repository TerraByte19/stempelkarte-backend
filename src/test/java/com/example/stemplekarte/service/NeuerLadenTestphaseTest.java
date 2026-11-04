package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import com.example.stemplekarte.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Jeder neu angelegte Laden startet mit 14 Tagen gratis (Tarif ohne Eintrag = Pro). */
class NeuerLadenTestphaseTest {

    @Test
    void register_setztVierzehnTage() {
        ShopRepository shopRepo = mock(ShopRepository.class);
        when(shopRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        PasswordEncoder enc = mock(PasswordEncoder.class);
        when(enc.encode(any())).thenReturn("hash");
        ShopService dienst = new ShopService(shopRepo, mock(StaffTokenRepository.class), enc, mock(JwtService.class));

        Instant vorher = Instant.now();
        Shop s = dienst.register("Neu@Laden.de", "geheim123", "Neuer Laden", 3);
        assertThat(s.getTestBis()).isBetween(vorher.plus(Duration.ofDays(14)), Instant.now().plus(Duration.ofDays(14)));
        assertThat(s.testTageUebrig(Instant.now())).isEqualTo(14);
        assertThat(s.getTarif().name()).isEqualTo("PRO");
    }
}
