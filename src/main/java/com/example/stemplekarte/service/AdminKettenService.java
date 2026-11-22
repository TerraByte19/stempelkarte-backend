package com.example.stemplekarte.service;

import com.example.stemplekarte.model.AdminZugriff;
import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.repository.AdminZugriffRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.StaffTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Was der Betreiber aus dem Admin-Panel an einer Kette tun darf: Ueberblick
 * ueber Filialen und ihre Logins, und eingreifen, wenn die Zentrale nicht
 * weiterkommt (Filiale anlegen, ein-/ausschalten, Login anlegen, Passwort
 * neu, sperren). Jeder Eingriff steht im Protokoll (AdminZugriff, Art KETTE).
 * Passwoerter stehen nie im Protokoll.
 *
 * Die Regeln selbst liegen in OrteService und FilialZugangService - hier
 * wird nichts doppelt geprueft.
 */
@Service
public class AdminKettenService {

    private final ShopRepository shops;
    private final OrteService orteService;
    private final FilialZugangService zugangService;
    private final StaffTokenRepository staffTokenRepo;
    private final AdminZugriffRepository protokoll;

    public AdminKettenService(ShopRepository shops, OrteService orteService,
                              FilialZugangService zugangService, StaffTokenRepository staffTokenRepo,
                              AdminZugriffRepository protokoll) {
        this.shops = shops;
        this.orteService = orteService;
        this.zugangService = zugangService;
        this.staffTokenRepo = staffTokenRepo;
        this.protokoll = protokoll;
    }

    public record Login(String id, String benutzername, boolean gesperrt, String zuletztEingeloggt) {}

    public record Filiale(String id, String label, String adresse, boolean haupt, boolean aktiv,
                          long geraete, List<Login> logins) {}

    public record Uebersicht(String shopId, String name, String tarif, boolean kette, boolean mailsAus,
                             boolean hauptIstFiliale, int geraeteJeFiliale,
                             int filialen, int filialenAktiv, int logins, List<Filiale> liste) {}

    private Shop laden(String shopId) {
        return shops.findById(shopId).orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden: " + shopId));
    }

    private void merke(Shop shop, String detail, String ip) {
        protokoll.save(AdminZugriff.create(shop.getId(), shop.getName(), AdminZugriff.KETTE, detail, ip));
    }

    @Transactional(readOnly = true)
    public Uebersicht uebersicht(String shopId) {
        Shop shop = laden(shopId);
        List<ShopOrt> orte = orteService.liste(shop);
        List<FilialZugang> zugaenge = zugangService.liste(shop);
        List<StaffToken> geraete = staffTokenRepo.findByShop(shop);

        List<Filiale> liste = new ArrayList<>();
        liste.add(filiale(null, FilialenService.HAUPT_LABEL, null, true, shop.isHauptIstFiliale(), zugaenge, geraete));
        for (ShopOrt o : orte) {
            liste.add(filiale(o.getId(), o.getLabel(), o.getAdresse(), false, o.isAktiv(), zugaenge, geraete));
        }
        int aktiv = (int) liste.stream().filter(Filiale::aktiv).count();
        return new Uebersicht(shop.getId(), shop.getName(), shop.getTarif().name(), OrteService.kette(shop),
                shop.isMailsAus(), shop.isHauptIstFiliale(), shop.getMaxTokensJeFiliale(),
                liste.size(), aktiv, zugaenge.size(), liste);
    }

    private static Filiale filiale(String ortId, String label, String adresse, boolean haupt, boolean aktiv,
                                   List<FilialZugang> zugaenge, List<StaffToken> geraete) {
        List<Login> logins = zugaenge.stream()
                .filter(z -> Objects.equals(z.getOrtId(), ortId))
                .map(z -> new Login(z.getId(), z.getBenutzername(), z.isGesperrt(),
                        z.getLastLoginAt() != null ? z.getLastLoginAt().toString() : null))
                .toList();
        long anzahl = geraete.stream().filter(t -> Objects.equals(t.getOrtId(), ortId)).count();
        return new Filiale(ortId != null ? ortId : FilialenService.HAUPT, label, adresse, haupt, aktiv, anzahl, logins);
    }

    @Transactional
    public Uebersicht filialeAnlegen(String shopId, String label, Double latitude, Double longitude, String ip) {
        Shop shop = laden(shopId);
        orteService.hinzufuegen(shopId, label, latitude, longitude);
        merke(shop, "Filiale angelegt: " + label, ip);
        return uebersicht(shopId);
    }

    @Transactional
    public Uebersicht filialeAktiv(String shopId, String ortId, boolean aktiv, String ip) {
        Shop shop = laden(shopId);
        orteService.aendern(shopId, ortId, null, null, null, aktiv);
        merke(shop, "Filiale " + ortId + (aktiv ? " eingeschaltet" : " ausgeschaltet"), ip);
        return uebersicht(shopId);
    }

    @Transactional
    public Uebersicht loginAnlegen(String shopId, String benutzername, String passwort, String ortId, String ip) {
        Shop shop = laden(shopId);
        FilialZugang z = zugangService.anlegen(shop, benutzername, passwort, ortId);
        merke(shop, "Login angelegt: " + z.getBenutzername(), ip);
        return uebersicht(shopId);
    }

    @Transactional
    public Uebersicht loginPasswort(String shopId, String zugangId, String passwort, String ip) {
        Shop shop = laden(shopId);
        FilialZugang z = zugangService.passwortSetzen(shop, zugangId, passwort);
        merke(shop, "Passwort neu gesetzt: " + z.getBenutzername(), ip);
        return uebersicht(shopId);
    }

    @Transactional
    public Uebersicht loginSperre(String shopId, String zugangId, boolean gesperrt, String ip) {
        Shop shop = laden(shopId);
        FilialZugang z = zugangService.sperren(shop, zugangId, gesperrt);
        merke(shop, "Login " + (gesperrt ? "gesperrt: " : "freigegeben: ") + z.getBenutzername(), ip);
        return uebersicht(shopId);
    }

    @Transactional
    public Uebersicht geraeteJeFiliale(String shopId, int anzahl, String ip) {
        Shop shop = laden(shopId);
        if (anzahl < 1 || anzahl > Shop.GERAETE_JE_FILIALE_MAX) {
            throw new IllegalArgumentException("Erlaubt sind 1 bis " + Shop.GERAETE_JE_FILIALE_MAX);
        }
        shop.setMaxTokensJeFiliale(anzahl);
        shops.save(shop);
        merke(shop, "Geräte je Filiale: " + anzahl, ip);
        return uebersicht(shopId);
    }
}
