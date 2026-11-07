package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.Stimme;
import com.example.stemplekarte.repository.StimmeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Stimmen von Laeden fuer die Startseite.
 *
 * Ablauf: Laden schreibt Zitat, Person (optional) und Ort in der App und
 * bestaetigt, dass es mit Ladenname und Ort auf stampit-app.de stehen darf.
 * Status OFFEN. Der Admin gibt frei oder lehnt ab. Die Website holt nur
 * FREIGEGEBEN. Aendert der Laden den Text, ist die Stimme wieder OFFEN.
 *
 * Der Admin aendert den Wortlaut nicht - ein frisiertes Zitat waere keine
 * echte Kundenstimme mehr. Passt etwas nicht, lehnt er ab, der Laden sieht
 * das in der App und kann neu einreichen.
 *
 * IllegalArgumentException = 400 mit Klartext fuer den Laden.
 */
@Service
public class StimmenService {

    private static final Logger log = LoggerFactory.getLogger(StimmenService.class);

    private final StimmeRepository repo;

    public StimmenService(StimmeRepository repo) {
        this.repo = repo;
    }

    /** Was der Laden schickt. */
    public record Eingabe(String zitat, String person, String ort, boolean einverstanden) {}

    /** Was der Laden sieht. status null = noch keine Stimme eingereicht. */
    public record Stand(String zitat, String person, String ort, String status,
                        String eingereichtAm, String entschiedenAm) {
        static Stand leer() { return new Stand(null, null, null, null, null, null); }
        static Stand von(Stimme s) {
            return new Stand(s.getZitat(), s.getPerson(), s.getOrt(), s.getStatus().name(),
                    s.getGeaendertAm().toString(),
                    s.getEntschiedenAm() != null ? s.getEntschiedenAm().toString() : null);
        }
    }

    /** Zeile im Admin. */
    public record AdminZeile(String id, String shopId, String laden, String email, String zitat, String person,
                             String ort, String status, String eingereichtAm, String einverstandenAm,
                             String entschiedenAm) {
        static AdminZeile von(Stimme s) {
            return new AdminZeile(s.getId(), s.getShop().getId(), s.getShop().getName(), s.getShop().getEmail(),
                    s.getZitat(), s.getPerson(), s.getOrt(), s.getStatus().name(),
                    s.getGeaendertAm().toString(), s.getEinverstandenAm().toString(),
                    s.getEntschiedenAm() != null ? s.getEntschiedenAm().toString() : null);
        }
    }

    /** Was die Startseite bekommt. Kein Shop-Schluessel, keine Mail, nichts Internes. */
    public record Oeffentlich(String zitat, String laden, String ort, String person) {}

    @Transactional(readOnly = true)
    public Stand meine(Shop shop) {
        return repo.findByShop(shop).map(Stand::von).orElse(Stand.leer());
    }

    @Transactional
    public Stand einreichen(Shop shop, Eingabe e, Instant jetzt) {
        if (e == null || !e.einverstanden()) {
            throw new IllegalArgumentException(
                    "Bitte bestätige, dass dein Zitat mit Ladenname und Ort auf stampit-app.de erscheinen darf.");
        }
        String zitat = ohneAnfuehrungszeichen(sauber(e.zitat(), Stimme.ZITAT_MAX + 20));
        if (zitat == null || zitat.length() < Stimme.ZITAT_MIN) {
            throw new IllegalArgumentException("Bitte schreib mindestens einen kurzen Satz.");
        }
        if (zitat.length() > Stimme.ZITAT_MAX) {
            throw new IllegalArgumentException("Das Zitat darf höchstens " + Stimme.ZITAT_MAX + " Zeichen haben.");
        }
        String ort = sauber(e.ort(), Stimme.ORT_MAX + 20);
        if (ort == null || ort.length() < 2) {
            throw new IllegalArgumentException("Bitte gib deinen Ort an, zum Beispiel „Berlin-Wedding“.");
        }
        if (ort.length() > Stimme.ORT_MAX) {
            throw new IllegalArgumentException("Der Ort darf höchstens " + Stimme.ORT_MAX + " Zeichen haben.");
        }
        String person = sauber(e.person(), Stimme.PERSON_MAX + 20);
        if (person != null && person.length() > Stimme.PERSON_MAX) {
            throw new IllegalArgumentException("Name und Rolle dürfen höchstens " + Stimme.PERSON_MAX + " Zeichen haben.");
        }

        Stimme s = repo.findByShop(shop).orElse(null);
        if (s == null) {
            s = Stimme.create(shop, zitat, person, ort, jetzt);
            log.info("Neue Stimme von Laden {} wartet auf Freigabe", shop.getId());
        } else {
            s.aendern(zitat, person, ort, jetzt);
            log.info("Stimme von Laden {} geändert, wartet wieder auf Freigabe", shop.getId());
        }
        return Stand.von(repo.save(s));
    }

    @Transactional
    public void zurueckziehen(Shop shop) {
        repo.findByShop(shop).ifPresent(s -> {
            repo.delete(s);
            log.info("Stimme von Laden {} zurückgezogen", shop.getId());
        });
    }

    @Transactional(readOnly = true)
    public List<AdminZeile> alle() {
        return repo.findAllByOrderByGeaendertAmDesc().stream().map(AdminZeile::von).toList();
    }

    @Transactional
    public AdminZeile freigeben(String id, Instant jetzt) {
        Stimme s = hole(id);
        s.freigeben(jetzt);
        log.info("Stimme {} von Laden {} freigegeben", id, s.getShop().getId());
        return AdminZeile.von(repo.save(s));
    }

    @Transactional
    public AdminZeile ablehnen(String id, Instant jetzt) {
        Stimme s = hole(id);
        s.ablehnen(jetzt);
        log.info("Stimme {} von Laden {} abgelehnt", id, s.getShop().getId());
        return AdminZeile.von(repo.save(s));
    }

    @Transactional(readOnly = true)
    public List<Oeffentlich> oeffentlich() {
        return repo.findTop6ByStatusOrderByEntschiedenAmDesc(Stimme.Status.FREIGEGEBEN).stream()
                .map(s -> new Oeffentlich(s.getZitat(), s.getShop().getName(), s.getOrt(), s.getPerson()))
                .toList();
    }

    private Stimme hole(String id) {
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException("Stimme nicht gefunden"));
    }

    /** Trimmen, Steuerzeichen raus, Leerraum zusammenziehen; null wenn leer. Hart abgeschnitten bei max. */
    static String sauber(String roh, int max) {
        if (roh == null) return null;
        String s = roh.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Die Seite setzt die Anfuehrungszeichen selbst; mitgeschickte am Rand fallen weg. */
    static String ohneAnfuehrungszeichen(String s) {
        if (s == null) return null;
        String t = s;
        while (!t.isEmpty() && "\"„“”'‚‘’«»".indexOf(t.charAt(0)) >= 0) t = t.substring(1);
        while (!t.isEmpty() && "\"„“”'‚‘’«»".indexOf(t.charAt(t.length() - 1)) >= 0) t = t.substring(0, t.length() - 1);
        t = t.trim();
        return t.isEmpty() ? null : t;
    }
}
