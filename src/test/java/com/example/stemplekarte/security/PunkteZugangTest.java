package com.example.stemplekarte.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft die Punkte-Endpunkte durch die ECHTE Filterkette.
 *
 * Diese Luecke hat die Punktekarte fast unbrauchbar ausgeliefert:
 * SecurityConfig endet mit anyRequest().denyAll(), und /api/points/** stand
 * nicht in der Freigabeliste. Jede Buchung kam als 403 zurueck, obwohl der
 * Staff-Token stimmte - die Endpunkte tauchten in /v3/api-docs auf, waren
 * aber ueber HTTP nicht erreichbar.
 *
 * Gemerkt wurde es nicht, weil PunkteControllerTest den Controller direkt
 * aufruft und die Filterkette dabei gar nicht laeuft. Genau deshalb steht
 * dieser Test hier: er geht den Weg, den der Scanner geht.
 *
 * Erwartet wird bewusst NICHT 200 - ohne echte Karte antwortet der
 * Controller mit 4xx. Entscheidend ist nur: die Anfrage kommt bis zum
 * Controller durch und wird nicht vorher mit 403 abgeraeumt.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PunkteZugangTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String QR = "{\"qrPayload\":\"{\\\"cid\\\":\\\"CUST-X\\\",\\\"cardId\\\":\\\"CARD-X\\\"}\"";

    @Test
    void punkteEndpunkteSindNichtDurchDieSicherheitsregelGesperrt() throws Exception {
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QR + ",\"amountCents\":1000}"))
                .andExpect(status().is(not403()));
    }

    @Test
    void einloesenEbenfalls() throws Exception {
        mockMvc.perform(post("/api/points/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QR + ",\"rewardId\":\"RW-X\"}"))
                .andExpect(status().is(not403()));
    }

    @Test
    void korrigierenEbenfalls() throws Exception {
        mockMvc.perform(post("/api/points/correct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QR + ",\"pointsX100\":100}"))
                .andExpect(status().is(not403()));
    }

    @Test
    void zuruecknehmenEbenfalls() throws Exception {
        mockMvc.perform(post("/api/points/undo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QR + ",\"bookingId\":\"PB-X\"}"))
                .andExpect(status().is(not403()));
    }

    @Test
    void scanStateEbenfalls() throws Exception {
        // Faellt unter /api/scan/** und war nie gesperrt - hier nur als
        // Gegenprobe, dass der Test ueberhaupt etwas misst.
        mockMvc.perform(post("/api/scan/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QR + "}"))
                .andExpect(status().is(not403()));
    }

    /** Ohne Staff-Token antwortet der Controller mit 401, ohne Karte mit
     *  404 - beides ist in Ordnung. Nur 403 bedeutet: die Sicherheitsregel
     *  hat die Anfrage abgeraeumt, bevor sie irgendwo ankam. */
    private static org.hamcrest.Matcher<Integer> not403() {
        return org.hamcrest.Matchers.not(403);
    }
}
