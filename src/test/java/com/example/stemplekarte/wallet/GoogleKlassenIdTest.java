package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.service.RewardService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Jede Karte bekommt ihre eigene Google-Klasse.
 *
 * Vorher gab es eine Klasse pro Laden. Die Klasse traegt den Kartennamen:
 * hatte ein Laden zwei Karten, schrieb die zuletzt gespeicherte ihren Namen
 * (und Logo, Farbe, Hero-Bild) auf die andere - auf Android stand dann der
 * Name der falschen Karte. Die alte Laden-Klasse bleibt als Ausweg fuer
 * Objekte, die schon bei Google liegen.
 */
class GoogleKlassenIdTest {

    private GoogleWalletService service() {
        AppProperties props = mock(AppProperties.class);
        GoogleWalletService s = new GoogleWalletService(props,
                mock(CustomerCardRepository.class), mock(RewardService.class));
        AppProperties.Google google = mock(AppProperties.Google.class);
        when(google.issuerId()).thenReturn("333");
        when(props.google()).thenReturn(google);
        return s;
    }

    private Card karte(String id) {
        Card card = mock(Card.class);
        when(card.getId()).thenReturn(id);
        return card;
    }

    @Test
    void zweiKartenDesselbenLadensTeilenKeineKlasse() {
        GoogleWalletService s = service();

        assertThat(s.classIdFor(karte("aaa-111"))).isEqualTo("333.card_aaa_111");
        assertThat(s.classIdFor(karte("bbb-222"))).isEqualTo("333.card_bbb_222");
    }

    @Test
    void alteLadenKlasseBleibtErreichbar() {
        GoogleWalletService s = service();
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("shop-1");

        assertThat(s.legacyClassIdFor(shop)).isEqualTo("333.shop_shop_1");
    }
}
