package com.example.stemplekarte.service;

import com.example.stemplekarte.model.AdminZugriff;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.AdminZugriffRepository;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.JwtService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * Zwei Dinge, die der Betreiber aus dem Admin-Panel an einem Laden-Konto tun
 * darf: das Dashboard des Ladens oeffnen (Support-Zugang) und die
 * Login-Adresse aendern. Beides wird protokolliert (AdminZugriff).
 *
 * <p>Alles am Laden haengt an der Laden-ID, nicht an der E-Mail: Karten,
 * Kunden, Paesse und laufende Logins bleiben beim Adresswechsel unberuehrt.
 * Die E-Mail ist Login-Name, Reply-To im Newsletter und Empfaenger der
 * Betreiber-Mails an den Laden.
 */
@Service
public class AdminZugangService {

    /** Bewusst schlicht: etwas@etwas.etwas ohne Leerzeichen. */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MAX_EMAIL = 200;

    private final ShopRepository shops;
    private final AdminZugriffRepository protokoll;
    private final JwtService jwt;

    public AdminZugangService(ShopRepository shops, AdminZugriffRepository protokoll, JwtService jwt) {
        this.shops = shops;
        this.protokoll = protokoll;
        this.jwt = jwt;
    }

    public record Zugang(String token, String shopId, String name, Instant bis) {}

    /**
     * Stellt einen Support-Token fuer den Laden aus (eine Stunde gueltig).
     * Das Passwort des Ladens wird weder gelesen noch geaendert, der Laden
     * bleibt selbst eingeloggt.
     *
     * <p>Erst protokollieren, dann den Token herausgeben: scheitert das
     * Protokoll, gibt es keinen Zugang.
     */
    @Transactional
    public Zugang oeffnen(String shopId, String ip) {
        Shop shop = laden(shopId);
        if (!shop.isActive()) {
            // Der JwtAuthFilter weist Tokens gesperrter Laeden ohnehin ab.
            throw new IllegalArgumentException("Laden ist gesperrt. Erst entsperren, dann öffnen.");
        }
        protokoll.save(AdminZugriff.create(shop.getId(), shop.getName(), AdminZugriff.ZUGANG, null, ip));
        Instant bis = Instant.now().plusMillis(JwtService.SUPPORT_EXPIRATION_MS);
        return new Zugang(jwt.generateSupportToken(shop.getId()), shop.getId(), shop.getName(), bis);
    }

    /**
     * Aendert die Login-Adresse eines Ladens. Gespeichert wird klein und ohne
     * Rand - genau so sucht ShopService.login.
     */
    @Transactional
    public Shop emailAendern(String shopId, String neueEmail, String ip) {
        Shop shop = laden(shopId);
        String neu = neueEmail == null ? "" : neueEmail.toLowerCase().trim();
        if (neu.isEmpty() || neu.length() > MAX_EMAIL || !EMAIL.matcher(neu).matches()) {
            throw new IllegalArgumentException("Das ist keine gültige E-Mail-Adresse.");
        }
        String alt = shop.getEmail();
        if (neu.equals(alt)) {
            throw new IllegalArgumentException("Das ist schon die Adresse dieses Ladens.");
        }
        if (shops.existsByEmail(neu)) {
            throw new IllegalArgumentException("Diese E-Mail gehört schon zu einem anderen Laden.");
        }
        shop.setEmail(neu);
        shops.save(shop);
        protokoll.save(AdminZugriff.create(shop.getId(), shop.getName(), AdminZugriff.EMAIL, alt + " -> " + neu, ip));
        return shop;
    }

    public List<AdminZugriff> letzte() {
        return protokoll.findTop200ByOrderByZeitpunktDesc();
    }

    private Shop laden(String shopId) {
        return shops.findById(shopId)
                .orElseThrow(() -> new NoSuchElementException("Shop nicht gefunden"));
    }
}
