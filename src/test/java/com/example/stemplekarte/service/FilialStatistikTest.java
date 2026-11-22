package com.example.stemplekarte.service;

import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.ShopRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Statistik je Filiale gegen eine echte (H2-)Datenbank: die Zahlen kommen
 * aus GROUP-BY-Abfragen, die sich mit Attrappen nicht pruefen lassen.
 */
@DataJpaTest
class FilialStatistikTest {

    @Autowired private ScanLogRepository scanLogs;
    @Autowired private ShopOrtRepository orte;
    @Autowired private ShopRepository shops;
    @Autowired private EntityManager em;

    private FilialStatistikService dienst;
    private Shop kette;
    private ShopOrt nord;
    private ShopOrt sued;
    private FilialStatistikService.Zeitraum woche;
    private Instant jetzt;

    @BeforeEach
    void setUp() {
        dienst = new FilialStatistikService(scanLogs, orte);
        kette = Shop.create("statistik@kette.test", "hash", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        kette = shops.save(kette);
        nord = orte.save(ShopOrt.create(kette, "Nord", 52.6, 13.4));
        sued = orte.save(ShopOrt.create(kette, "Süd", 52.4, 13.4));
        jetzt = Instant.now();
        LocalDate heute = FilialStatistikService.heute();
        woche = new FilialStatistikService.Zeitraum(heute.minusDays(6), heute);
    }

    /** Scan mit frei gesetztem Zeitpunkt (scannedAt ist sonst immer "jetzt"). */
    private void scan(String kunde, String ortId, int stempel, int eingeloest, Instant wann) {
        ScanLog sl = scanLogs.save(ScanLog.create(kette.getId(), "CARD-1", kunde, stempel, 0, "Kasse", ortId)
                .eingeloest(eingeloest));
        em.flush();
        em.createQuery("update ScanLog s set s.scannedAt = :t where s.id = :id")
                .setParameter("t", wann).setParameter("id", sl.getId()).executeUpdate();
        em.clear();
    }

    private Map<String, FilialStatistikService.Zeile> jeId(FilialStatistikService.Tabelle t) {
        return t.filialen().stream().collect(Collectors.toMap(FilialStatistikService.Zeile::id, Function.identity()));
    }

    @Test
    void zaehltJeFiliale_stempelNeueKundenPraemienAktive() {
        Instant gestern = jetzt.minus(1, ChronoUnit.DAYS);
        Instant vorMonat = jetzt.minus(40, ChronoUnit.DAYS);

        // Kunde A: alt (erster Scan vor 40 Tagen in Nord), kommt diese Woche in Nord wieder.
        scan("A", nord.getId(), 1, 0, vorMonat);
        scan("A", nord.getId(), 2, 0, gestern);
        // Kunde B: neu diese Woche in Nord, danach auch in Sued.
        scan("B", nord.getId(), 1, 0, gestern);
        scan("B", sued.getId(), 1, 0, gestern.plus(1, ChronoUnit.HOURS));
        // Kunde C: neu in Sued, loest in demselben Scan eine Praemie ein
        // (stampsAdded zaehlt den Einloese-Durchlauf mit: 3 gemeldet, 2 echte Stempel).
        scan("C", sued.getId(), 3, 1, gestern);
        // Kunde D: neu am Hauptstandort.
        scan("D", null, 1, 0, gestern);

        FilialStatistikService.Tabelle t = dienst.tabelle(kette, woche);
        Map<String, FilialStatistikService.Zeile> z = jeId(t);

        assertThat(t.filialen()).extracting(FilialStatistikService.Zeile::id)
                .containsExactly("HAUPT", nord.getId(), sued.getId());

        FilialStatistikService.Zeile n = z.get(nord.getId());
        assertThat(n.scans()).isEqualTo(2);
        assertThat(n.stempel()).isEqualTo(3);
        assertThat(n.neueKunden()).isEqualTo(1);     // nur B, A ist alt
        assertThat(n.aktiveKunden()).isEqualTo(2);   // A und B
        assertThat(n.praemien()).isZero();

        FilialStatistikService.Zeile s = z.get(sued.getId());
        assertThat(s.scans()).isEqualTo(2);
        assertThat(s.stempel()).isEqualTo(3);        // B: 1, C: 3 - 1 eingeloest
        assertThat(s.neueKunden()).isEqualTo(1);     // nur C; B war zuerst in Nord
        assertThat(s.aktiveKunden()).isEqualTo(2);   // B und C
        assertThat(s.praemien()).isEqualTo(1);

        FilialStatistikService.Zeile h = z.get("HAUPT");
        assertThat(h.haupt()).isTrue();
        assertThat(h.neueKunden()).isEqualTo(1);
        assertThat(h.stempel()).isEqualTo(1);

        // Summe: aktive Kunden werden NICHT addiert (B war in zwei Filialen).
        assertThat(t.gesamt().scans()).isEqualTo(5);
        assertThat(t.gesamt().stempel()).isEqualTo(7);
        assertThat(t.gesamt().neueKunden()).isEqualTo(3);
        assertThat(t.gesamt().praemien()).isEqualTo(1);
        assertThat(t.gesamt().aktiveKunden()).isEqualTo(4);
    }

    @Test
    void unterkonto_siehtNurDieEigeneZeile() {
        Instant gestern = jetzt.minus(1, ChronoUnit.DAYS);
        scan("A", nord.getId(), 2, 0, gestern);
        scan("B", sued.getId(), 5, 1, gestern);
        scan("C", null, 4, 0, gestern);

        FilialStatistikService.Zeile n = dienst.eine(kette, nord.getId(), "Nord", woche);
        assertThat(n.id()).isEqualTo(nord.getId());
        assertThat(n.label()).isEqualTo("Nord");
        assertThat(n.stempel()).isEqualTo(2);
        assertThat(n.neueKunden()).isEqualTo(1);
        assertThat(n.aktiveKunden()).isEqualTo(1);
        assertThat(n.praemien()).isZero();

        FilialStatistikService.Zeile h = dienst.eine(kette, null, "Hauptstandort", woche);
        assertThat(h.id()).isEqualTo("HAUPT");
        assertThat(h.stempel()).isEqualTo(4);

        // Filiale ohne Scans: lauter Nullen, kein Fehler.
        ShopOrt leer = orte.save(ShopOrt.create(kette, "Leer", 52.0, 13.0));
        assertThat(dienst.eine(kette, leer.getId(), "Leer", woche).scans()).isZero();
    }

    @Test
    void fremderLadenUndAndererZeitraum_zaehlenNicht() {
        Shop fremd = shops.save(Shop.create("fremd@laden.test", "hash", "Fremd", 3));
        scanLogs.save(ScanLog.create(fremd.getId(), "CARD-9", "X", 9, 0, "Kasse", nord.getId()));
        scan("A", nord.getId(), 1, 0, jetzt.minus(20, ChronoUnit.DAYS));
        em.flush();

        FilialStatistikService.Tabelle t = dienst.tabelle(kette, woche);
        assertThat(t.gesamt().scans()).isZero();
        assertThat(t.gesamt().aktiveKunden()).isZero();
    }

    @Test
    void ausgeschalteteUndGeloeschteFilialen_undZentraleOhneKunden() {
        Instant gestern = jetzt.minus(1, ChronoUnit.DAYS);
        scan("A", nord.getId(), 1, 0, gestern);
        scan("B", "ORT-GELOESCHT", 1, 0, gestern);
        sued.setAktiv(false);
        orte.save(sued);
        kette.setHauptIstFiliale(false);
        shops.save(kette);
        em.flush();

        FilialStatistikService.Tabelle t = dienst.tabelle(kette, woche);
        // Sued: aus und ohne Scans -> keine Zeile. Hauptstandort: keine Filiale, keine Scans -> keine Zeile.
        assertThat(t.filialen()).extracting(FilialStatistikService.Zeile::id)
                .containsExactly(nord.getId(), "ORT-GELOESCHT");
        assertThat(jeId(t).get("ORT-GELOESCHT").geloescht()).isTrue();
        assertThat(jeId(t).get("ORT-GELOESCHT").label()).isEqualTo(FilialenService.GELOESCHT_LABEL);
    }

    @Test
    void zeitraum_standardGrenzenUndFehler() {
        LocalDate heute = LocalDate.of(2026, 11, 22);
        FilialStatistikService.Zeitraum z = FilialStatistikService.zeitraum(null, null, heute);
        assertThat(z.bis()).isEqualTo(heute);
        assertThat(z.von()).isEqualTo(LocalDate.of(2026, 10, 24));   // 30 Tage einschliesslich heute

        z = FilialStatistikService.zeitraum("2026-11-01", "2026-11-07", heute);
        assertThat(z.von()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(z.bis()).isEqualTo(LocalDate.of(2026, 11, 7));
        // Beide Tage zaehlen mit: Ende ist Mitternacht NACH dem letzten Tag.
        assertThat(z.ende()).isAfter(z.start().plus(6, ChronoUnit.DAYS));

        assertThatThrownBy(() -> FilialStatistikService.zeitraum("2026-11-08", "2026-11-07", heute))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilialStatistikService.zeitraum("gestern", null, heute))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilialStatistikService.zeitraum("2020-01-01", "2026-11-07", heute))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("400");
    }
}
