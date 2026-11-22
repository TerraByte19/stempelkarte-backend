package com.example.stemplekarte.service;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.FilialZugangRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Unterkonten der Filialen einer Kette. Verwaltet vom Hauptkonto (und vom
 * Admin). Bewusst ohne E-Mail: Passwoerter vergibt und erneuert das
 * Hauptkonto.
 */
@Service
public class FilialZugangService {

    public static final int PASSWORT_MIN = 8;
    public static final String LOGIN_FALSCH = "Benutzername oder Passwort falsch";
    public static final String FILIALE_AUS = FilialenService.FILIALE_AUS;
    static final String GESPERRT = "Dieser Zugang ist gesperrt.";
    static final String NUR_KETTE = "Unterkonten gibt es nur im Tarif Kette.";

    private final FilialZugangRepository zugaenge;
    private final ShopOrtRepository orte;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public FilialZugangService(FilialZugangRepository zugaenge, ShopOrtRepository orte,
                               PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.zugaenge = zugaenge;
        this.orte = orte;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public record Anmeldung(String token, FilialZugang zugang, String ortLabel) {}

    @Transactional
    public Anmeldung login(String benutzername, String passwort) {
        String name;
        try {
            name = FilialZugang.benutzernameSauber(benutzername);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(LOGIN_FALSCH);
        }
        FilialZugang z = zugaenge.findWithShopByBenutzername(name)
                .orElseThrow(() -> new IllegalArgumentException(LOGIN_FALSCH));
        if (!passwordEncoder.matches(passwort, z.getPasswortHash())) {
            throw new IllegalArgumentException(LOGIN_FALSCH);
        }
        Shop shop = z.getShop();
        if (!shop.isActive()) throw new IllegalArgumentException("Account ist deaktiviert");
        if (z.isGesperrt() || shop.getTarif() != Tarif.KETTE) throw new IllegalArgumentException(GESPERRT);
        if (!ortAktiv(z)) throw new IllegalArgumentException(FILIALE_AUS);
        z.eingeloggt(Instant.now());
        zugaenge.save(z);
        return new Anmeldung(
                jwtService.generateFilialToken(z.getId(), shop.getId(), z.getTokenVersion()),
                z, ortLabel(z));
    }

    @Transactional
    public FilialZugang anlegen(Shop shop, String benutzername, String passwort, String ortId) {
        if (shop.getTarif() != Tarif.KETTE) throw new IllegalArgumentException(NUR_KETTE);
        String name = FilialZugang.benutzernameSauber(benutzername);
        pruefePasswort(passwort);
        String ort = ortSauber(shop, ortId);
        if (ort == null && !shop.isHauptIstFiliale()) {
            throw new IllegalArgumentException("Der Hauptstandort ist keine Filiale. Bitte eine Filiale wählen.");
        }
        if (zugaenge.existsByBenutzername(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dieser Benutzername ist schon vergeben.");
        }
        return zugaenge.save(FilialZugang.create(shop, name, passwordEncoder.encode(passwort), ort));
    }

    public List<FilialZugang> liste(Shop shop) {
        return zugaenge.findByShopOrderByCreatedAtAsc(shop);
    }

    @Transactional
    public FilialZugang passwortSetzen(Shop shop, String id, String passwort) {
        FilialZugang z = eigener(shop, id);
        pruefePasswort(passwort);
        z.neuesPasswort(passwordEncoder.encode(passwort));
        return zugaenge.save(z);
    }

    @Transactional
    public FilialZugang sperren(Shop shop, String id, boolean gesperrt) {
        FilialZugang z = eigener(shop, id);
        z.sperre(gesperrt);
        return zugaenge.save(z);
    }

    @Transactional
    public void loeschen(Shop shop, String id) {
        zugaenge.delete(eigener(shop, id));
    }

    /** Hauptstandort oder der Name der Filiale. */
    public String ortLabel(FilialZugang z) {
        if (z.getOrtId() == null) return FilialenService.HAUPT_LABEL;
        return orte.findById(z.getOrtId()).map(ShopOrt::getLabel).orElse(FilialenService.GELOESCHT_LABEL);
    }

    /**
     * Hauptstandort: an, solange er als Filiale zaehlt (Shop.hauptIstFiliale).
     * Eine geloeschte Filiale ist aus. Dieselbe Regel steht im JwtAuthFilter.
     */
    public boolean ortAktiv(FilialZugang z) {
        if (z.getOrtId() == null) return z.getShop().isHauptIstFiliale();
        return orte.findById(z.getOrtId()).map(ShopOrt::isAktiv).orElse(false);
    }

    private FilialZugang eigener(Shop shop, String id) {
        return zugaenge.findByIdAndShop(id, shop)
                .orElseThrow(() -> new NoSuchElementException("Zugang nicht gefunden"));
    }

    /** Leer oder "HAUPT" = Hauptstandort. Sonst muss die Filiale zum Laden gehoeren. */
    private String ortSauber(Shop shop, String ortId) {
        if (ortId == null || ortId.isBlank() || FilialenService.HAUPT.equals(ortId.trim())) return null;
        return orte.findByIdAndShop(ortId.trim(), shop)
                .orElseThrow(() -> new NoSuchElementException("Filiale nicht gefunden"))
                .getId();
    }

    private static void pruefePasswort(String passwort) {
        if (passwort == null || passwort.length() < PASSWORT_MIN) {
            throw new IllegalArgumentException("Das Passwort braucht mindestens " + PASSWORT_MIN + " Zeichen.");
        }
    }
}
