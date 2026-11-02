package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Weitere Orte: Name und Koordinaten geprueft, hoechstens neun, nur eigene loeschen. */
class OrteServiceTest {

    private Shop shop;
    private ShopOrtRepository ortRepo;
    private OrteService dienst;

    @BeforeEach
    void setUp() {
        shop = Shop.create("a@b.de", "hash", "Laden", 3);
        shop.setMaxOrte(9);
        ShopRepository shopRepo = mock(ShopRepository.class);
        when(shopRepo.findById(shop.getId())).thenReturn(Optional.of(shop));
        ortRepo = mock(ShopOrtRepository.class);
        dienst = new OrteService(ortRepo, shopRepo);
    }

    @Test
    void hinzufuegen_speichertGetrimmt() {
        dienst.hinzufuegen(shop.getId(), "  Wochenmarkt\n ", 52.52, 13.405);
        ArgumentCaptor<ShopOrt> ort = ArgumentCaptor.captor();
        verify(ortRepo).save(ort.capture());
        assertThat(ort.getValue().getLabel()).isEqualTo("Wochenmarkt");
        assertThat(ort.getValue().getLatitude()).isEqualTo(52.52);
        assertThat(ort.getValue().getShop()).isSameAs(shop);
    }

    @Test
    void hinzufuegen_prueftNameUndKoordinaten() {
        assertThatThrownBy(() -> dienst.hinzufuegen(shop.getId(), " ", 52.52, 13.405))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.hinzufuegen(shop.getId(), "x".repeat(41), 52.52, 13.405))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("40");
        assertThatThrownBy(() -> dienst.hinzufuegen(shop.getId(), "Markt", null, 13.405))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.hinzufuegen(shop.getId(), "Markt", 91.0, 13.405))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.hinzufuegen(shop.getId(), "Markt", 52.52, 181.0))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ortRepo, never()).save(any());
    }

    @Test
    void hoechstensNeun() {
        when(ortRepo.countByShop(shop)).thenReturn(9L);
        assertThatThrownBy(() -> dienst.hinzufuegen(shop.getId(), "Markt", 52.52, 13.405))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("9");
        verify(ortRepo, never()).save(any());
    }

    @Test
    void tarif_entscheidet() {
        shop.setMaxOrte(0);
        assertThatThrownBy(() -> dienst.hinzufuegen(shop.getId(), "Markt", 52.52, 13.405))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Tarif");
        shop.setMaxOrte(2);
        when(ortRepo.countByShop(shop)).thenReturn(2L);
        assertThatThrownBy(() -> dienst.hinzufuegen(shop.getId(), "Markt", 52.52, 13.405))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2");
        verify(ortRepo, never()).save(any());
        // Mehr als Apple zulaesst gibt es nie, egal was der Admin eintraegt.
        shop.setMaxOrte(50);
        assertThat(OrteService.erlaubt(shop)).isEqualTo(9);
    }

    @Test
    void loeschen_nurEigene() {
        ShopOrt ort = ShopOrt.create(shop, "Markt", 52.52, 13.405);
        when(ortRepo.findByIdAndShop(ort.getId(), shop)).thenReturn(Optional.of(ort));
        dienst.loeschen(shop.getId(), ort.getId());
        verify(ortRepo).delete(ort);
        assertThatThrownBy(() -> dienst.loeschen(shop.getId(), "ORT-FREMD"))
                .isInstanceOf(NoSuchElementException.class);
    }
}
