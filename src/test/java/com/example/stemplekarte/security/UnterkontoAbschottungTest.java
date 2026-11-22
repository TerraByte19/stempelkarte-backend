package com.example.stemplekarte.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Ein Unterkonto (ROLE_FILIALE) kommt an KEINEN einzigen Endpunkt unter
 * /api/shop. Die Liste kommt aus den echten Controllern: jeder neue Endpunkt
 * ist automatisch mitgeprueft. Umgekehrt kommt der Inhaber nicht in den
 * Bereich der Filialen, und ohne Login kommt dort niemand hin.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnterkontoAbschottungTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    private record Aufruf(HttpMethod methode, String pfad) {}

    private List<Aufruf> endpunkteUnter(String vorsilbe) {
        List<Aufruf> liste = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (info.getPathPatternsCondition() == null) return;
            for (String muster : info.getPathPatternsCondition().getPatternValues()) {
                if (!muster.startsWith(vorsilbe)) continue;
                String pfad = muster.replaceAll("\\{[^}]+}", "x");
                for (RequestMethod m : info.getMethodsCondition().getMethods()) {
                    liste.add(new Aufruf(HttpMethod.valueOf(m.name()), pfad));
                }
            }
        });
        return liste;
    }

    @Test
    void unterkonto_kommtAnKeinenLadenEndpunkt() throws Exception {
        List<Aufruf> alle = endpunkteUnter("/api/shop");
        // Sicherung gegen einen Test, der still nichts prueft.
        assertThat(alle.size()).as("gefundene /api/shop-Endpunkte").isGreaterThan(80);

        for (Aufruf a : alle) {
            int status = mockMvc.perform(request(a.methode(), a.pfad()).with(user("filiale").roles("FILIALE")))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(a.methode() + " " + a.pfad()).isEqualTo(403);
        }
    }

    @Test
    void inhaber_kommtNichtInDenFilialBereich() throws Exception {
        List<Aufruf> alle = endpunkteUnter("/api/filiale");
        assertThat(alle).isNotEmpty();
        for (Aufruf a : alle) {
            int status = mockMvc.perform(request(a.methode(), a.pfad()).with(user("laden").roles("SHOP")))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(a.methode() + " " + a.pfad()).isEqualTo(403);
        }
    }

    @Test
    void ohneLogin_istDerFilialBereichZu() throws Exception {
        for (Aufruf a : endpunkteUnter("/api/filiale")) {
            int status = mockMvc.perform(request(a.methode(), a.pfad()))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(a.methode() + " " + a.pfad()).isIn(401, 403);
        }
    }

    @Test
    void scannerGeraet_kommtNichtInDenFilialBereich() throws Exception {
        for (Aufruf a : endpunkteUnter("/api/filiale")) {
            int status = mockMvc.perform(request(a.methode(), a.pfad()).with(user("geraet").roles("STAFF")))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(a.methode() + " " + a.pfad()).isEqualTo(403);
        }
    }
}
