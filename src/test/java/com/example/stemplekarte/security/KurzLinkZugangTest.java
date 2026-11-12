package com.example.stemplekarte.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kurze Kundenadresse "/CARD-..." durch die ECHTE Filterkette (siehe
 * PunkteZugangTest, warum): sie muss dieselbe Anmeldeseite liefern wie
 * "/karte-neu/CARD-...", und sonst darf an der Wurzel nichts aufgehen.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KurzLinkZugangTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void kurzeAdresse_liefertDieselbeSeiteWieDieLange() throws Exception {
        var lang = mockMvc.perform(get("/karte-neu/CARD-GIBTSNICHT")).andReturn().getResponse();
        var kurz = mockMvc.perform(get("/CARD-GIBTSNICHT")).andReturn().getResponse();
        assertThat(kurz.getStatus()).isNotEqualTo(403).isEqualTo(lang.getStatus());
        assertThat(kurz.getContentAsString()).isEqualTo(lang.getContentAsString());
    }

    @Test
    void sonstBleibtDieWurzelZu() throws Exception {
        mockMvc.perform(get("/irgendwas")).andExpect(status().isForbidden());
        mockMvc.perform(get("/CARD-X/unterseite")).andExpect(status().isForbidden());
        mockMvc.perform(get("/card-x")).andExpect(status().isForbidden());
    }
}
