package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.StaffTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Scanner-Geraete EINER Filiale, verwaltet vom Unterkonto dieser Filiale.
 * Die Filiale (ortId, null = Hauptstandort) kommt immer aus dem Login und
 * nie aus der Anfrage: ein Unterkonto sieht, aendert und loescht nur
 * Geraete, die an seiner eigenen Filiale haengen, und kann ein Geraet nicht
 * an eine andere Filiale haengen.
 *
 * Ein Geraet wird ueber seine Kennung angesprochen (StaffToken.getKennung),
 * nicht ueber das Token. Fremde und unbekannte Geraete sind gleich "nicht
 * gefunden" - am Fehler laesst sich nicht ablesen, was es gibt.
 */
@Service
public class FilialGeraeteService {

    private final StaffTokenRepository staffTokenRepo;
    private final TeamService teamService;

    public FilialGeraeteService(StaffTokenRepository staffTokenRepo, TeamService teamService) {
        this.staffTokenRepo = staffTokenRepo;
        this.teamService = teamService;
    }

    /** token: das Unterkonto braucht ihn, um das Geraet einzurichten (Link/QR). */
    public record Geraet(String kennung, String label, String rolle, String erstellt,
                         String zuletzt, String token) {
        static Geraet von(StaffToken t) {
            return new Geraet(t.getKennung(), t.getLabel(), t.getRolle(),
                    t.getCreatedAt() != null ? t.getCreatedAt().toString() : null,
                    t.getLastUsedAt() != null ? t.getLastUsedAt().toString() : null,
                    t.getToken());
        }
    }

    @Transactional(readOnly = true)
    public List<Geraet> liste(Shop shop, String ortId) {
        return eigene(shop, ortId).stream()
                .sorted(Comparator.comparing(StaffToken::getCreatedAt))
                .map(Geraet::von).toList();
    }

    /** Obergrenze je Filiale prueft der TeamService (Shop.maxTokensJeFiliale). */
    @Transactional
    public Geraet anlegen(Shop shop, String ortId, String label, String rolle) {
        if (!OrteService.kette(shop)) throw new IllegalArgumentException(OrteService.NUR_KETTE);
        return Geraet.von(teamService.anlegen(shop, label, rolle,
                ortId != null ? ortId : FilialenService.HAUPT));
    }

    /** Null = Feld nicht anfassen. Die Filiale laesst sich hier nicht aendern. */
    @Transactional
    public Geraet aendern(Shop shop, String ortId, String kennung, String label, String rolle) {
        StaffToken t = eigenes(shop, ortId, kennung);
        if (label != null) t.umbenennen(TeamService.labelSauber(label));
        if (rolle != null) t.rolle(rolle);
        return Geraet.von(staffTokenRepo.save(t));
    }

    @Transactional
    public void entfernen(Shop shop, String ortId, String kennung) {
        staffTokenRepo.delete(eigenes(shop, ortId, kennung));
    }

    private List<StaffToken> eigene(Shop shop, String ortId) {
        return staffTokenRepo.findByShop(shop).stream()
                .filter(t -> Objects.equals(t.getOrtId(), ortId))
                .toList();
    }

    private StaffToken eigenes(Shop shop, String ortId, String kennung) {
        return eigene(shop, ortId).stream()
                .filter(t -> t.getKennung().equals(kennung))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Gerät nicht gefunden"));
    }
}
