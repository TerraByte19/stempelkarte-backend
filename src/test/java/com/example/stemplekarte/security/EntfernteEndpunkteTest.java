package com.example.stemplekarte.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Rueckmeldung und "Kunde wirbt Kunde" sind entfernt. Ein aelteres Frontend
 * (noch im Browser-Cache oder vor dem Frontend-Deploy) ruft die Adressen
 * weiter auf. Die Antwort darf dann kein 401/403 sein: darauf meldet das
 * Frontend den Laden ab (api.js) - er kaeme nicht mehr ins Dashboard.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EntfernteEndpunkteTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void entfernteAdressen_meldenDenLadenNichtAb() throws Exception {
        for (String pfad : new String[]{"/api/shop/rueckmeldungen", "/api/shop/cards/CARD-1/empfehlung"}) {
            int status = mockMvc.perform(get(pfad).with(user("laden").roles("SHOP")))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(pfad).isNotIn(401, 403);
        }
    }
}
