package com.example.stemplekarte.service;

import com.example.stemplekarte.model.ScannerMessung;
import com.example.stemplekarte.repository.ScannerMessungRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stille Scanner-Messung: Runden annehmen (vom Scanner, je Stempel eine)
 * und fuer den Admin je Geraetetyp und Herausforderer zusammenfassen.
 */
@Service
public class ScannerMessungService {

    static final Set<String> GEGNER = ScannerMessung.GEGNER;
    /** Mehr speichert ein Laden pro Tag nicht - Schutz vor Amoklauf eines Geraets. */
    static final int MAX_JE_LADEN_UND_TAG = 2000;
    static final int ZEITRAUM_TAGE = 60;

    private final ScannerMessungRepository repo;

    public ScannerMessungService(ScannerMessungRepository repo) {
        this.repo = repo;
    }

    /** Null = verworfen (unplausibel oder Tageslimit). */
    public ScannerMessung annehmen(String shopId, String staffLabel, String plattform, String geraet,
                                   String gegner, Integer altMs, Integer gegnerMs, String aktiv, Instant jetzt) {
        return annehmen(shopId, staffLabel, plattform, geraet, gegner, altMs, gegnerMs, aktiv,
                null, null, null, null, jetzt);
    }

    /** Wie oben, dazu Lage des Geraets (hoch/quer), Groesse des Kamerabilds und App oder Browser. */
    public ScannerMessung annehmen(String shopId, String staffLabel, String plattform, String geraet,
                                   String gegner, Integer altMs, Integer gegnerMs, String aktiv,
                                   String lage, Integer bildBreite, Integer bildHoehe, Boolean app, Instant jetzt) {
        if (shopId == null || gegner == null || !GEGNER.contains(gegner)) return null;
        if (altMs == null || altMs < 0 || altMs > 60_000) return null;
        if (gegnerMs != null && (gegnerMs < 0 || gegnerMs > 60_000)) return null;
        if (repo.countByShopIdAndErstelltAmAfter(shopId, jetzt.minus(Duration.ofDays(1))) >= MAX_JE_LADEN_UND_TAG) {
            return null;
        }
        return repo.save(ScannerMessung.create(shopId, staffLabel, plattform, geraet, gegner, altMs, gegnerMs, aktiv, jetzt)
                .mitLage(lage, bildBreite, bildHoehe, app));
    }

    /**
     * Zeile der Lage-Tabelle im Admin: je Laden, Geraetetyp, Lage und App/Browser.
     * trefferMs = Median bis zum ersten Treffer (egal welcher Leser).
     * bildGedreht = Runden, in denen das Kamerabild nicht zur Lage passte.
     */
    public record LageZeile(String shopId, String geraet, String lage, Boolean app, long runden,
                            int trefferMs, long bildGedreht, String zuletzt) {}

    /** Hochformat gegen Querformat, letzte 60 Tage. Nur Runden, die die Lage mitgeschickt haben. */
    public List<LageZeile> lagen(Instant jetzt) {
        return lagen(repo.findTop20000ByErstelltAmAfterOrderByErstelltAmDesc(jetzt.minus(Duration.ofDays(ZEITRAUM_TAGE))));
    }

    private record LageGruppe(String shopId, String geraet, String lage, Boolean app) {}

    static List<LageZeile> lagen(List<ScannerMessung> messungen) {
        Map<LageGruppe, List<ScannerMessung>> gruppen = new LinkedHashMap<>();
        for (ScannerMessung m : messungen) {
            if (m.getLage() == null) continue;
            gruppen.computeIfAbsent(new LageGruppe(m.getShopId(), m.geraeteTyp(), m.getLage(), m.getApp()),
                    k -> new ArrayList<>()).add(m);
        }
        List<LageZeile> out = new ArrayList<>();
        for (var e : gruppen.entrySet()) {
            List<ScannerMessung> l = e.getValue();
            List<Integer> treffer = l.stream()
                    .map(m -> m.getGegnerMs() == null ? m.getAltMs() : Math.min(m.getAltMs(), m.getGegnerMs()))
                    .sorted().toList();
            int median = treffer.size() % 2 == 1 ? treffer.get(treffer.size() / 2)
                    : (treffer.get(treffer.size() / 2 - 1) + treffer.get(treffer.size() / 2)) / 2;
            long gedreht = l.stream().filter(m -> Boolean.FALSE.equals(m.bildPasstZurLage())).count();
            LageGruppe g = e.getKey();
            out.add(new LageZeile(g.shopId(), g.geraet(), g.lage(), g.app(), l.size(), median, gedreht,
                    l.get(0).getErstelltAm().toString()));
        }
        out.sort((a, b) -> {
            int c = a.shopId().compareTo(b.shopId());
            return c != 0 ? c : Long.compare(b.runden(), a.runden());
        });
        return out;
    }

    /** Zeile der Admin-Tabelle. */
    public record Zeile(String geraet, String gegner, long runden, long zuerst, int anteilProzent,
                        Integer vorsprungMs, long verfehlt, long gewechselt, String zuletzt) {}

    /** Zusammenfassung der letzten 60 Tage, je Geraetetyp und Herausforderer. */
    public List<Zeile> zusammenfassung(Instant jetzt) {
        return zusammenfassung(repo.findTop20000ByErstelltAmAfterOrderByErstelltAmDesc(jetzt.minus(Duration.ofDays(ZEITRAUM_TAGE))));
    }

    private record Gruppe(String geraet, String gegner) {}

    static List<Zeile> zusammenfassung(List<ScannerMessung> messungen) {
        Map<Gruppe, List<ScannerMessung>> gruppen = new LinkedHashMap<>();
        for (ScannerMessung m : messungen) {
            gruppen.computeIfAbsent(new Gruppe(m.geraeteTyp(), m.getGegner()), k -> new ArrayList<>()).add(m);
        }
        List<Zeile> out = new ArrayList<>();
        for (var e : gruppen.entrySet()) {
            List<ScannerMessung> l = e.getValue();
            long zuerst = l.stream().filter(m -> m.getGegnerMs() != null && m.getGegnerMs() < m.getAltMs()).count();
            long verfehlt = l.stream().filter(m -> m.getGegnerMs() == null).count();
            long gewechselt = l.stream().filter(m -> m.getGegner().equals(m.getAktiv())).count();
            List<Integer> vorspruenge = l.stream().filter(m -> m.getGegnerMs() != null)
                    .map(m -> m.getAltMs() - m.getGegnerMs()).sorted().toList();
            Integer median = vorspruenge.isEmpty() ? null
                    : vorspruenge.size() % 2 == 1 ? vorspruenge.get(vorspruenge.size() / 2)
                    : (vorspruenge.get(vorspruenge.size() / 2 - 1) + vorspruenge.get(vorspruenge.size() / 2)) / 2;
            out.add(new Zeile(e.getKey().geraet(), e.getKey().gegner(), l.size(), zuerst, (int) Math.round(100.0 * zuerst / l.size()),
                    median, verfehlt, gewechselt, l.get(0).getErstelltAm().toString()));
        }
        out.sort((a, b) -> Long.compare(b.runden(), a.runden()));
        return out;
    }
}
