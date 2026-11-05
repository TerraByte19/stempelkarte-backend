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

    static final Set<String> GEGNER = Set.of("neu", "nimiq", "handy");
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
        if (shopId == null || gegner == null || !GEGNER.contains(gegner)) return null;
        if (altMs == null || altMs < 0 || altMs > 60_000) return null;
        if (gegnerMs != null && (gegnerMs < 0 || gegnerMs > 60_000)) return null;
        if (repo.countByShopIdAndErstelltAmAfter(shopId, jetzt.minus(Duration.ofDays(1))) >= MAX_JE_LADEN_UND_TAG) {
            return null;
        }
        return repo.save(ScannerMessung.create(shopId, staffLabel, plattform, geraet, gegner, altMs, gegnerMs, aktiv, jetzt));
    }

    /** Zeile der Admin-Tabelle. */
    public record Zeile(String geraet, String gegner, long runden, long zuerst, int anteilProzent,
                        Integer vorsprungMs, long verfehlt, long gewechselt, String zuletzt) {}

    /** Zusammenfassung der letzten 60 Tage, je Geraetetyp und Herausforderer. */
    public List<Zeile> zusammenfassung(Instant jetzt) {
        return zusammenfassung(repo.findByErstelltAmAfterOrderByErstelltAmDesc(jetzt.minus(Duration.ofDays(ZEITRAUM_TAGE))));
    }

    static List<Zeile> zusammenfassung(List<ScannerMessung> messungen) {
        Map<String, List<ScannerMessung>> gruppen = new LinkedHashMap<>();
        for (ScannerMessung m : messungen) {
            gruppen.computeIfAbsent(m.geraeteTyp() + "|" + m.getGegner(), k -> new ArrayList<>()).add(m);
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
            String[] teile = e.getKey().split("\\|", 2);
            out.add(new Zeile(teile[0], teile[1], l.size(), zuerst, (int) Math.round(100.0 * zuerst / l.size()),
                    median, verfehlt, gewechselt, l.get(0).getErstelltAm().toString()));
        }
        out.sort((a, b) -> Long.compare(b.runden(), a.runden()));
        return out;
    }
}
