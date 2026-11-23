package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kette: eine Karte gilt nur in den Filialen, die das Hauptkonto gewaehlt
 * hat. Ohne Auswahl ueberall, und ausserhalb der Kette wird nie geprueft.
 */
class KartenGeltungTest {

    private CustomerCardRepository ccRepo;
    private CardRepository cardRepo;
    private ShopOrtRepository ortRepo;
    private KartenGeltung geltung;
    private Shop kette;
    private ShopOrt nord;
    private ShopOrt sued;
    private Card karte;
    private CustomerCard cc;
    private String qr;

    @BeforeEach
    void aufbau() {
        ccRepo = mock(CustomerCardRepository.class);
        cardRepo = mock(CardRepository.class);
        ortRepo = mock(ShopOrtRepository.class);
        geltung = new KartenGeltung(ccRepo, cardRepo, ortRepo);

        kette = Shop.create("z@kette.de", "HASH", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        nord = ShopOrt.create(kette, "Nord", 52.5, 13.4);
        sued = ShopOrt.create(kette, "Sued", 48.1, 11.6);
        when(ortRepo.findByIdAndShop(nord.getId(), kette)).thenReturn(Optional.of(nord));
        when(ortRepo.findByIdAndShop(sued.getId(), kette)).thenReturn(Optional.of(sued));

        karte = Card.create(kette, "Brotkarte", "x", 10, "1 Brot gratis");
        cc = CustomerCard.create(Customer.create("Ayşe", "ayse@example.com"), karte);
        when(ccRepo.findById(cc.getId())).thenReturn(Optional.of(cc));
        when(cardRepo.findById(karte.getId())).thenReturn(Optional.of(karte));
        qr = QrPayload.fuer(cc);
    }

    @Test
    void ohneAuswahl_giltDieKarteUeberall() {
        assertThat(KartenGeltung.stand(karte).alle()).isTrue();
        assertThat(karte.giltIn(null)).isTrue();
        assertThat(karte.giltIn(nord.getId())).isTrue();
        assertThatCode(() -> geltung.pruefe(kette, nord.getId(), qr)).doesNotThrowAnyException();
        assertThatCode(() -> geltung.pruefe(kette, null, qr)).doesNotThrowAnyException();
    }

    @Test
    void nurHauptstandort_filialenWerdenAbgelehnt() {
        geltung.setzen(kette, karte, false, List.of("haupt"));
        assertThat(KartenGeltung.stand(karte).filialen()).containsExactly("HAUPT");

        assertThatCode(() -> geltung.pruefe(kette, null, qr)).doesNotThrowAnyException();
        assertThatThrownBy(() -> geltung.pruefe(kette, nord.getId(), qr))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(KartenGeltung.GILT_NICHT);
        assertThatThrownBy(() -> geltung.pruefeKundenkarte(kette, nord.getId(), cc.getId()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void zweiFilialen_dieDritteUndDerHauptstandortNicht() {
        geltung.setzen(kette, karte, false, List.of(nord.getId(), sued.getId(), nord.getId()));
        assertThat(KartenGeltung.stand(karte).filialen()).containsExactly(nord.getId(), sued.getId());

        assertThatCode(() -> geltung.pruefe(kette, sued.getId(), qr)).doesNotThrowAnyException();
        assertThatThrownBy(() -> geltung.pruefe(kette, null, qr)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> geltung.pruefe(kette, "ORT-WEST", qr)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void alleHebtDieAuswahlWiederAuf() {
        geltung.setzen(kette, karte, false, List.of(nord.getId()));
        geltung.setzen(kette, karte, true, List.of(nord.getId()));
        assertThat(karte.getGiltIn()).isNull();
        assertThatCode(() -> geltung.pruefe(kette, sued.getId(), qr)).doesNotThrowAnyException();
    }

    @Test
    void leereAuswahlUndFremdeFiliale_werdenAbgelehnt_karteBleibt() {
        assertThatThrownBy(() -> geltung.setzen(kette, karte, false, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mindestens eine");
        when(ortRepo.findByIdAndShop("ORT-FREMD", kette)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> geltung.setzen(kette, karte, false, List.of(nord.getId(), "ORT-FREMD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(karte.getGiltIn()).isNull();
    }

    @Test
    void andereTarife_keineEinstellungUndKeinePruefung() {
        Shop pro = Shop.create("p@laden.de", "HASH", "Einzelladen", 3);
        Card proKarte = Card.create(pro, "Kaffee", "x", 10, "Kaffee");
        assertThatThrownBy(() -> geltung.setzen(pro, proKarte, false, List.of("HAUPT")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Kette");

        // Selbst eine (nie setzbare) Auswahl wird ausserhalb der Kette nicht
        // geprueft, und die Datenbank wird gar nicht erst gefragt.
        karte.setGiltIn(List.of(nord.getId()));
        kette.setTarif(Tarif.PRO);
        CustomerCardRepository unberuehrt = mock(CustomerCardRepository.class);
        KartenGeltung g = new KartenGeltung(unberuehrt, cardRepo, ortRepo);
        assertThatCode(() -> g.pruefe(kette, null, qr)).doesNotThrowAnyException();
        verifyNoInteractions(unberuehrt);
    }

    @Test
    void unlesbarerQrCode_istHierKeinFehler() {
        geltung.setzen(kette, karte, false, List.of(nord.getId()));
        assertThatCode(() -> geltung.pruefe(kette, null, "kein-qr-code")).doesNotThrowAnyException();
        assertThatCode(() -> geltung.pruefe(kette, null, "SK:CC-GIBTESNICHT")).doesNotThrowAnyException();
    }
}
