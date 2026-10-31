package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Texte der eigenen Google-Mitteilungen. Ersetzen Googles "Points hat sich
 * veraendert" - der Kunde soll Laden und Stand direkt lesen.
 */
class GoogleMeldungTest {

    private CustomerCard karte(int stempel) {
        Shop laden = mock(Shop.class);
        when(laden.getName()).thenReturn("KING OF CUT");
        CustomerCard cc = CustomerCard.create(null, Card.create(laden, "Haarschnitt", "x", 8, "Gratis Schnitt"));
        for (int i = 0; i < stempel; i++) cc.addStamp();
        return cc;
    }

    @Test
    void neuerStempel_zeigtLadenUndStand() {
        String[] text = GoogleWalletService.meldungText(karte(4), GoogleWalletService.Meldung.STEMPEL);
        assertThat(text[0]).isEqualTo("Neuer Stempel bei KING OF CUT");
        assertThat(text[1]).isEqualTo("Du hast jetzt 4 von 8 Stempeln.");
    }

    @Test
    void karteVoll_nenntBelohnung() {
        String[] text = GoogleWalletService.meldungText(karte(8), GoogleWalletService.Meldung.VOLL);
        assertThat(text[0]).isEqualTo("🎉 Karte voll!");
        assertThat(text[1]).startsWith("Gratis Schnitt verdient!");
    }

    @Test
    void keine_ohneText() {
        assertThat(GoogleWalletService.meldungText(karte(1), GoogleWalletService.Meldung.KEINE)).isNull();
    }
}
