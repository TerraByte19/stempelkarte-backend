package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Terminanfrage;
import com.example.stemplekarte.repository.TerminanfrageRepository;
import com.example.stemplekarte.service.Drossel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Vorfuehrung buchen (Startseite): offener Endpunkt mit Honigtopf und
 * Drossel, Liste und Status im Admin. Bestaetigt wird von Hand.
 *
 * IllegalArgumentException = 400 mit Klartext; die Seite zeigt ihn an.
 */
@Tag(name = "Termine", description = "Terminanfragen fuer Vorfuehrungen von der Startseite")
@RestController
public class TerminController {

    private static final Logger log = LoggerFactory.getLogger(TerminController.class);
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Pattern UHRZEIT = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    static final int MAX_TAGE_VORAUS = 90;
    static final int MAX_FELD = 200;

    private final TerminanfrageRepository repo;
    /** Drei Anfragen je Adresse in zehn Minuten - mehr braucht kein Mensch. */
    private final Drossel drossel = new Drossel(3, Duration.ofMinutes(10));

    public TerminController(TerminanfrageRepository repo) {
        this.repo = repo;
    }

    public record TerminRequest(String name, String shop, String email, String phone, String art,
                                String date, String time, String address, String message,
                                String language, String website) {}

    @Operation(summary = "Vorfuehrung anfragen",
            description = "art IM_LADEN|TELEFON|VIDEO, date ISO (morgen bis +90 Tage), time HH:mm, "
                    + "email oder phone Pflicht. website = Honigtopf, bleibt leer.")
    @PostMapping("/api/public/termin")
    public ResponseEntity<Void> anfragen(@RequestBody TerminRequest req,
                                         @RequestHeader(value = "X-Forwarded-For", required = false) String forwarded) {
        return anfragen(req, forwarded, Instant.now());
    }

    ResponseEntity<Void> anfragen(TerminRequest req, String forwarded, Instant jetzt) {
        // Honigtopf: nur Bots fuellen das Feld. 200, damit der Bot nichts merkt.
        if (req.website() != null && !req.website().isBlank()) {
            log.info("Terminanfrage verworfen (Honigtopf)");
            return ResponseEntity.ok().build();
        }
        Terminanfrage t = pruefen(req, jetzt);
        drossel.pruefe(clientKey(forwarded), jetzt);
        repo.save(t);
        log.info("Terminanfrage {} von {} ({}) fuer {} {}", t.getId(), t.getLaden(), t.getArt(), t.getWunschAm(), t.getWunschZeit());
        return ResponseEntity.ok().build();
    }

    /** Baut die Anfrage oder wirft 400 mit Klartext. Paketprivat fuer den Test. */
    static Terminanfrage pruefen(TerminRequest req, Instant jetzt) {
        String name = feld(req.name(), MAX_FELD, "Bitte deinen Namen angeben.");
        String laden = feld(req.shop(), MAX_FELD, "Bitte den Namen deines Ladens angeben.");
        String email = sauber(req.email(), MAX_FELD);
        String telefon = sauber(req.phone(), 40);
        if (email == null && telefon == null) {
            throw new IllegalArgumentException("Bitte Telefon oder E-Mail angeben, eins reicht.");
        }
        if (email != null && !siehtAusWieMail(email)) {
            throw new IllegalArgumentException("Die E-Mail-Adresse sieht nicht richtig aus.");
        }
        Terminanfrage.Art art;
        try {
            art = Terminanfrage.Art.valueOf(req.art() == null ? "" : req.art().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Bitte waehlen: im Laden, Telefon oder Video.");
        }
        LocalDate tag;
        try {
            tag = LocalDate.parse(req.date() == null ? "" : req.date().trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Bitte einen Wunschtag waehlen.");
        }
        LocalDate heute = jetzt.atZone(BERLIN).toLocalDate();
        if (!tag.isAfter(heute)) {
            throw new IllegalArgumentException("Der Wunschtag muss in der Zukunft liegen, fruehestens morgen.");
        }
        if (tag.isAfter(heute.plusDays(MAX_TAGE_VORAUS))) {
            throw new IllegalArgumentException("Bitte einen Tag in den naechsten " + MAX_TAGE_VORAUS + " Tagen waehlen.");
        }
        String zeit = req.time() == null ? "" : req.time().trim();
        if (!UHRZEIT.matcher(zeit).matches()) {
            throw new IllegalArgumentException("Bitte eine Uhrzeit waehlen.");
        }
        String adresse = sauber(req.address(), 300);
        if (art == Terminanfrage.Art.IM_LADEN && adresse == null) {
            throw new IllegalArgumentException("Fuer eine Vorfuehrung im Laden brauchen wir die Adresse.");
        }
        String nachricht = sauber(req.message(), 1000);
        String sprache = req.language() == null ? "de" : req.language().trim().toLowerCase();
        if (!sprache.matches("^[a-z]{2}$")) sprache = "de";
        return Terminanfrage.create(name, laden, email, telefon, art, tag, zeit, adresse, nachricht, sprache, jetzt);
    }

    // ---- Admin ----

    @GetMapping("/api/admin/termine")
    public List<Map<String, Object>> alle() {
        return repo.findTop200ByOrderByEingegangenAmDesc().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("laden", t.getLaden());
            m.put("email", t.getEmail());
            m.put("telefon", t.getTelefon());
            m.put("art", t.getArt().name());
            m.put("wunschAm", t.getWunschAm().toString());
            m.put("wunschZeit", t.getWunschZeit());
            m.put("adresse", t.getAdresse());
            m.put("nachricht", t.getNachricht());
            m.put("sprache", t.getSprache());
            m.put("eingegangenAm", t.getEingegangenAm().toString());
            m.put("status", t.getStatus().name());
            return m;
        }).toList();
    }

    public record StatusRequest(String status) {}

    @PostMapping("/api/admin/termine/{id}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String id, @RequestBody StatusRequest req) {
        Terminanfrage.Status neu;
        try {
            neu = Terminanfrage.Status.valueOf(req.status() == null ? "" : req.status().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannter Status");
        }
        return repo.findById(id).map(t -> {
            t.setStatus(neu);
            repo.save(t);
            return ResponseEntity.ok(Map.<String, Object>of("status", t.getStatus().name()));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/admin/termine/{id}")
    public ResponseEntity<Void> loeschen(@PathVariable String id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Helfer ----

    private static String feld(String roh, int max, String fehler) {
        String s = sauber(roh, max + 50);
        if (s == null) throw new IllegalArgumentException(fehler);
        if (s.length() > max) throw new IllegalArgumentException("Eingabe zu lang (hoechstens " + max + " Zeichen).");
        return s;
    }

    /** Trimmen, Steuerzeichen raus, Leerraum zusammen; null wenn leer; hart abgeschnitten bei max. */
    static String sauber(String roh, int max) {
        if (roh == null) return null;
        String s = roh.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static boolean siehtAusWieMail(String s) {
        int at = s.indexOf('@');
        return at > 0 && at < s.length() - 1 && s.indexOf('.', at) > at + 1 && !s.contains(" ");
    }

    private static String clientKey(String forwarded) {
        if (forwarded == null || forwarded.isBlank()) return "unbekannt";
        return forwarded.split(",")[0].trim();
    }
}
