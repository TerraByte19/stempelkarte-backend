package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.KorrekturLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.AppleDeviceRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.KorrekturLogRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unterkonto: Geraete und Stempel-Korrektur sind auf die EIGENE Filiale
 * begrenzt. Nichts von einer anderen Filiale ist sichtbar oder aenderbar.
 */
class FilialGeraeteUndKorrekturTest {

    private Shop kette;
    private ShopOrt nord;
    private ShopOrt sued;
    private List<StaffToken> tokens;
    private StaffTokenRepository tokenRepo;
    private ShopOrtRepository ortRepo;
    private FilialGeraeteService geraete;

    private CustomerCardRepository ccRepo;
    private KorrekturLogRepository logRepo;
    private FilialKorrekturService korrektur;
    private Card card;

    @BeforeEach
    void setUp() {
        kette = Shop.create("z@kette.de", "hash", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        nord = ShopOrt.create(kette, "Nord", 52.6, 13.4);
        sued = ShopOrt.create(kette, "Süd", 52.4, 13.4);

        ortRepo = mock(ShopOrtRepository.class);
        when(ortRepo.findByIdAndShop(nord.getId(), kette)).thenReturn(Optional.of(nord));
        when(ortRepo.findByIdAndShop(sued.getId(), kette)).thenReturn(Optional.of(sued));
        when(ortRepo.findByShopOrderByCreatedAtAsc(kette)).thenReturn(List.of(nord, sued));

        tokens = new ArrayList<>();
        tokenRepo = mock(StaffTokenRepository.class);
        when(tokenRepo.findByShop(kette)).thenAnswer(a -> new ArrayList<>(tokens));
        when(tokenRepo.save(any())).thenAnswer(a -> {
            StaffToken t = a.getArgument(0);
            if (!tokens.contains(t)) tokens.add(t);
            return t;
        });
        TeamService team = new TeamService(tokenRepo, mock(ScanLogRepository.class),
                mock(ShopService.class), ortRepo);
        geraete = new FilialGeraeteService(tokenRepo, team);

        ccRepo = mock(CustomerCardRepository.class);
        when(ccRepo.save(any())).thenAnswer(a -> a.getArgument(0));
        logRepo = mock(KorrekturLogRepository.class);
        KundenService kunden = new KundenService(ccRepo, mock(AppleDeviceRepository.class),
                mock(ScanLogRepository.class), mock(ShopRepository.class));
        korrektur = new FilialKorrekturService(ccRepo, kunden, logRepo, ortRepo);
        card = Card.create(kette, "Brotkarte", "10 Stempel", 10, "1 Brot gratis");
    }

    private StaffToken vorhanden(String label, String ortId) {
        StaffToken t = StaffToken.create(kette, label);
        t.ort(ortId);
        tokens.add(t);
        return t;
    }

    private CustomerCard kundenkarte(Card karte, int stempel) {
        Customer c = mock(Customer.class);
        when(c.getId()).thenReturn("CUST-1");
        CustomerCard cc = CustomerCard.create(c, karte);
        for (int i = 0; i < stempel; i++) cc.addStamp();
        when(ccRepo.findById(cc.getId())).thenReturn(Optional.of(cc));
        when(ccRepo.findByCustomer_IdAndCard_Id("CUST-1", karte.getId())).thenReturn(Optional.of(cc));
        return cc;
    }

    // ── Geraete ─────────────────────────────────────────────────────────

    @Test
    void liste_nurDieEigeneFiliale() {
        vorhanden("Kasse Nord 1", nord.getId());
        vorhanden("Kasse Nord 2", nord.getId());
        vorhanden("Kasse Süd", sued.getId());
        vorhanden("Zentrale", null);

        assertThat(geraete.liste(kette, nord.getId())).extracting(FilialGeraeteService.Geraet::label)
                .containsExactlyInAnyOrder("Kasse Nord 1", "Kasse Nord 2");
        assertThat(geraete.liste(kette, null)).extracting(FilialGeraeteService.Geraet::label)
                .containsExactly("Zentrale");
    }

    @Test
    void anlegen_haengtAnDerEigenenFiliale_mitGrenzeJeFiliale() {
        kette.setMaxTokensJeFiliale(2);
        FilialGeraeteService.Geraet g = geraete.anlegen(kette, nord.getId(), "  Kasse 1 ", "AUSHILFE");
        assertThat(g.label()).isEqualTo("Kasse 1");
        assertThat(g.rolle()).isEqualTo("AUSHILFE");
        assertThat(g.token()).isNotBlank();
        assertThat(g.kennung()).isNotBlank().isNotEqualTo(g.token());
        assertThat(tokens.get(0).getOrtId()).isEqualTo(nord.getId());

        geraete.anlegen(kette, nord.getId(), "Kasse 2", null);
        assertThatThrownBy(() -> geraete.anlegen(kette, nord.getId(), "Kasse 3", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2");
        // Die Grenze gilt je Filiale: Sued hat noch Platz.
        geraete.anlegen(kette, sued.getId(), "Kasse Süd", null);
        assertThat(tokens).hasSize(3);
    }

    @Test
    void aendernUndEntfernen_nurEigeneGeraete() {
        StaffToken eigenes = vorhanden("Kasse Nord", nord.getId());
        StaffToken fremd = vorhanden("Kasse Süd", sued.getId());

        geraete.aendern(kette, nord.getId(), eigenes.getKennung(), "Theke", "KIOSK");
        assertThat(eigenes.getLabel()).isEqualTo("Theke");
        assertThat(eigenes.getRolle()).isEqualTo("KIOSK");
        assertThat(eigenes.getOrtId()).isEqualTo(nord.getId());

        // Fremdes Geraet: per Kennung nicht erreichbar, und schon gar nicht per Token.
        assertThatThrownBy(() -> geraete.aendern(kette, nord.getId(), fremd.getKennung(), "Gekapert", null))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> geraete.entfernen(kette, nord.getId(), fremd.getKennung()))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> geraete.entfernen(kette, nord.getId(), eigenes.getToken()))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(fremd.getLabel()).isEqualTo("Kasse Süd");
        verify(tokenRepo, never()).delete(any());

        geraete.entfernen(kette, nord.getId(), eigenes.getKennung());
        verify(tokenRepo).delete(eigenes);
    }

    @Test
    void kennung_istStabilUndVerraetDasTokenNicht() {
        StaffToken t = vorhanden("Kasse", nord.getId());
        assertThat(t.getKennung()).hasSize(16).isEqualTo(StaffToken.kennung(t.getToken()));
        assertThat(t.getToken()).doesNotContain(t.getKennung());
    }

    @Test
    void hauptkonto_legtFuerKettenJeFilialeAn_ohneDieLadenGrenze() {
        // Shop.maxTokens ist 3 - fuer eine Kette zaehlt aber die Grenze je Filiale.
        TeamService team = new TeamService(tokenRepo, mock(ScanLogRepository.class),
                mock(ShopService.class), ortRepo);
        for (int i = 0; i < 4; i++) team.anlegen(kette, "Kasse " + i, null, i % 2 == 0 ? nord.getId() : sued.getId());
        assertThat(tokens).hasSize(4);
    }

    // ── Korrektur ───────────────────────────────────────────────────────

    @Test
    void karte_zeigtStandOhneKundenname() {
        CustomerCard cc = kundenkarte(card, 4);
        FilialKorrekturService.Ansicht a = korrektur.karte(kette, QrPayload.fuer(cc));
        assertThat(a.customerCardId()).isEqualTo(cc.getId());
        assertThat(a.karte()).isEqualTo("Brotkarte");
        assertThat(a.stempel()).isEqualTo(4);
        assertThat(a.schwelle()).isEqualTo(10);
        assertThat(a.korrigierbar()).isTrue();
        assertThat(a.toString()).doesNotContain("CUST-1");
    }

    @Test
    void korrigieren_schreibtFilialeUndLoginInsProtokoll() {
        CustomerCard cc = kundenkarte(card, 4);
        FilialZugang zugang = FilialZugang.create(kette, "backbaron-nord", "HASH", nord.getId());

        FilialKorrekturService.Ergebnis e = korrektur.korrigieren(kette, zugang, cc.getId(), 5);
        assertThat(e.geaendert()).isTrue();
        assertThat(e.ansicht().stempel()).isEqualTo(5);

        ArgumentCaptor<KorrekturLog> log = ArgumentCaptor.forClass(KorrekturLog.class);
        verify(logRepo).save(log.capture());
        assertThat(log.getValue().getShopId()).isEqualTo(kette.getId());
        assertThat(log.getValue().getOrtId()).isEqualTo(nord.getId());
        assertThat(log.getValue().getWer()).isEqualTo("backbaron-nord");
        assertThat(log.getValue().getVorher()).isEqualTo(4);
        assertThat(log.getValue().getNachher()).isEqualTo(5);
        assertThat(log.getValue().getCustomerCardId()).isEqualTo(cc.getId());
    }

    @Test
    void korrigieren_gleicherStand_keinProtokoll_falscherBereich_keineAenderung() {
        CustomerCard cc = kundenkarte(card, 4);
        FilialZugang zugang = FilialZugang.create(kette, "backbaron-nord", "HASH", nord.getId());

        assertThat(korrektur.korrigieren(kette, zugang, cc.getId(), 4).geaendert()).isFalse();
        assertThatThrownBy(() -> korrektur.korrigieren(kette, zugang, cc.getId(), 11))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cc.getStamps()).isEqualTo(4);
        verify(logRepo, never()).save(any());
    }

    @Test
    void karteEinesAnderenLadens_gibtEsNicht() {
        Shop fremd = Shop.create("f@laden.de", "hash", "Fremd", 3);
        Card fremdeKarte = Card.create(fremd, "Fremdkarte", "10 Stempel", 10, "x");
        CustomerCard cc = kundenkarte(fremdeKarte, 3);
        FilialZugang zugang = FilialZugang.create(kette, "backbaron-nord", "HASH", nord.getId());

        assertThatThrownBy(() -> korrektur.karte(kette, QrPayload.fuer(cc)))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> korrektur.korrigieren(kette, zugang, cc.getId(), 5))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(cc.getStamps()).isEqualTo(3);
    }

    @Test
    void protokoll_fuerDasHauptkonto_mitFilialName() {
        KorrekturLog a = KorrekturLog.create(kette.getId(), nord.getId(), "CC-1", "backbaron-nord", 4, 5);
        KorrekturLog b = KorrekturLog.create(kette.getId(), null, "CC-2", "backbaron-haupt", 9, 0);
        KorrekturLog c = KorrekturLog.create(kette.getId(), "ORT-WEG", "CC-3", "backbaron-alt", 1, 2);
        when(logRepo.findTop100ByShopIdOrderByErstelltAmDesc(kette.getId())).thenReturn(List.of(a, b, c));

        List<FilialKorrekturService.Eintrag> p = korrektur.protokoll(kette);
        assertThat(p).extracting(FilialKorrekturService.Eintrag::ortLabel)
                .containsExactly("Nord", FilialenService.HAUPT_LABEL, FilialenService.GELOESCHT_LABEL);
        assertThat(p.get(1).ortId()).isEqualTo("HAUPT");
        assertThat(p.get(0).vorher()).isEqualTo(4);
        assertThat(p.get(0).nachher()).isEqualTo(5);
    }
}
