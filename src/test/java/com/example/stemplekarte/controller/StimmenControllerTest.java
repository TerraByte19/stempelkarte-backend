package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.StimmenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Der Controller reicht an den Dienst durch; der oeffentliche Weg ist cachebar. */
class StimmenControllerTest {

    private final StimmenService dienst = mock(StimmenService.class);
    private final StimmenController controller = new StimmenController(dienst);
    private final Shop shop = Shop.create("laden@localhost.test", "hash", "Café Nord", 3);

    private Authentication auth() {
        Authentication a = mock(Authentication.class);
        when(a.getPrincipal()).thenReturn(new JwtAuthFilter.ShopPrincipal(shop));
        return a;
    }

    @Test
    void ladenWege_nutzenDenEingeloggtenLaden() {
        StimmenService.Eingabe e = new StimmenService.Eingabe("Ein Zitat für die Website.", null, "Berlin", true);
        controller.einreichen(e, auth());
        verify(dienst).einreichen(eq(shop), eq(e), any(Instant.class));

        controller.meine(auth());
        verify(dienst).meine(shop);

        ResponseEntity<Void> res = controller.zurueckziehen(auth());
        verify(dienst).zurueckziehen(shop);
        assertThat(res.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void adminWege() {
        controller.alle();
        verify(dienst).alle();
        controller.freigeben("STM-1");
        verify(dienst).freigeben(eq("STM-1"), any(Instant.class));
        controller.ablehnen("STM-2");
        verify(dienst).ablehnen(eq("STM-2"), any(Instant.class));
    }

    @Test
    void oeffentlich_mitCache() {
        when(dienst.oeffentlich()).thenReturn(List.of(
                new StimmenService.Oeffentlich("Zitat", "Café Nord", "Berlin", null)));
        ResponseEntity<List<StimmenService.Oeffentlich>> res = controller.oeffentlich();
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getCacheControl()).contains("max-age=300").contains("public");
        assertThat(res.getBody()).hasSize(1);
    }
}
