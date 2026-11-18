package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.wallet.CloudinaryService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mehrere Stempel-Bilder ueber POST und PUT /cards/{id}/stamp-icons.
 *
 * Zwei Dinge duerfen nicht passieren: ein Bild landet bei Cloudinary, obwohl
 * die Karte schon voll ist, und ueber das Sortieren kommt eine fremde Adresse
 * an die Karte (der Server ruft diese Adressen spaeter beim Pass-Bau ab).
 */
class StempelBilderControllerTest {

    private final CardService cardService = mock(CardService.class);
    private final CloudinaryService cloudinary = mock(CloudinaryService.class);
    private final StampDesignController controller = new StampDesignController(
            mock(ShopRepository.class), cardService, cloudinary, mock(GoogleWalletService.class));

    private final Card card = Card.create(mock(Shop.class), "Karte", "Beschreibung", 10, "Gratis");

    private Authentication auth() {
        when(cardService.getByIdAndShop(anyString(), any())).thenReturn(card);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new JwtAuthFilter.ShopPrincipal(mock(Shop.class)));
        return auth;
    }

    private StampDesignController.ImageUploadRequest bild() {
        return new StampDesignController.ImageUploadRequest("QUJD", "png", null);
    }

    @Test
    void zweitesBildWirdHintenAngehaengtDasErsteBleibt() {
        card.setStampIconUrl("https://bild/erdbeere.png");
        when(cloudinary.upload(anyString(), anyString(), eq(CloudinaryService.ImageType.STAMP)))
                .thenReturn("https://bild/keks.png");

        Map<String, Object> antwort = controller.addCardStampIcon("CARD-1", bild(), auth());

        assertThat(card.getStampIconListe())
                .containsExactly("https://bild/erdbeere.png", "https://bild/keks.png");
        assertThat(card.getStampIconUrl()).isEqualTo("https://bild/erdbeere.png");
        assertThat(antwort).containsEntry("stampIconUrls",
                List.of("https://bild/erdbeere.png", "https://bild/keks.png"));
        verify(cardService).save(card);
    }

    @Test
    void vollKarteLehntAbOhneHochzuladen() {
        Authentication auth = auth();
        List<String> voll = new java.util.ArrayList<>();
        for (int i = 0; i < Card.MAX_STEMPEL_BILDER; i++) voll.add("https://bild/" + i + ".png");
        card.setStampIconListe(voll);

        assertThatThrownBy(() -> controller.addCardStampIcon("CARD-1", bild(), auth))
                .isInstanceOf(ResponseStatusException.class);

        verify(cloudinary, never()).upload(anyString(), anyString(), any());
        verify(cardService, never()).save(any());
        assertThat(card.getStampIconListe()).hasSize(Card.MAX_STEMPEL_BILDER);
    }

    @Test
    void reihenfolgeAendernUndEntfernen() {
        card.setStampIconListe(List.of("https://bild/a.png", "https://bild/b.png", "https://bild/c.png"));

        controller.setCardStampIcons("CARD-1",
                new StampDesignController.StampIconsRequest(List.of("https://bild/c.png", "https://bild/a.png")),
                auth());

        assertThat(card.getStampIconListe()).containsExactly("https://bild/c.png", "https://bild/a.png");
        assertThat(card.getStampIconUrl()).isEqualTo("https://bild/c.png");
    }

    @Test
    void fremdeAdresseWirdAbgelehnt() {
        Authentication auth = auth();
        card.setStampIconListe(List.of("https://bild/a.png"));

        assertThatThrownBy(() -> controller.setCardStampIcons("CARD-1",
                new StampDesignController.StampIconsRequest(
                        List.of("https://bild/a.png", "https://169.254.169.254/geheim")), auth))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(card.getStampIconListe()).containsExactly("https://bild/a.png");
        verify(cardService, never()).save(any());
    }

    @Test
    void doppelteNullUndFehlendeListeWerdenAbgelehnt() {
        Authentication auth = auth();
        card.setStampIconListe(List.of("https://bild/a.png", "https://bild/b.png"));

        assertThatThrownBy(() -> controller.setCardStampIcons("CARD-1",
                new StampDesignController.StampIconsRequest(
                        List.of("https://bild/a.png", "https://bild/a.png")), auth))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.setCardStampIcons("CARD-1",
                new StampDesignController.StampIconsRequest(
                        Arrays.asList("https://bild/a.png", null)), auth))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.setCardStampIcons("CARD-1",
                new StampDesignController.StampIconsRequest(null), auth))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(card.getStampIconListe()).containsExactly("https://bild/a.png", "https://bild/b.png");
    }

    @Test
    void alleEntfernenIstErlaubt() {
        card.setStampIconListe(List.of("https://bild/a.png"));

        controller.setCardStampIcons("CARD-1",
                new StampDesignController.StampIconsRequest(List.of()), auth());

        assertThat(card.getStampIconListe()).isEmpty();
    }

    @Test
    void designAntwortEnthaeltDieListe() {
        card.setStampIconListe(List.of("https://bild/a.png", "https://bild/b.png"));

        Map<String, Object> design = controller.getCardDesign("CARD-1", auth());

        assertThat(design).containsEntry("stampIconUrls",
                List.of("https://bild/a.png", "https://bild/b.png"));
        assertThat(design).containsEntry("stampIconUrl", "https://bild/a.png");
    }
}
