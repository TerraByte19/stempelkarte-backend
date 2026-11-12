package com.example.stemplekarte.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Die Adresse, die Kunden zu sehen bekommen (QR im Laden, Aufkleber, Mails,
 * Links auf der Wallet-Karte).
 *
 * Ohne eigene Adresse bleibt alles wie bisher: Serveradresse plus
 * "/karte-neu/CARD-...". Ist {@code stempelkarte.kunden-url} gesetzt
 * (Render: APP_KUNDEN_URL, z. B. https://karte.stampit-app.de), stehen in
 * allen Kundenlinks diese Adresse und der kurze Pfad "/CARD-...".
 *
 * Alte Links, gedruckte QR-Codes und Aufkleber laufen weiter: die
 * Serveradresse bleibt erreichbar, und "/karte-neu/..." bleibt bestehen.
 * Technische Adressen (Apple-Wallet-Webdienst, Pass-Download) bleiben
 * absichtlich auf der Serveradresse - bestehende Karten haengen daran.
 *
 * Statisch, weil die Links an vielen Stellen entstehen (Dienste, Pass,
 * Google-Objekt), die ihre Serveradresse schon selbst tragen.
 */
@Component
public class KundenLink {

    private static final Logger log = LoggerFactory.getLogger(KundenLink.class);

    private static volatile String eigene = null;

    public KundenLink(@Value("${stempelkarte.kunden-url:}") String kundenUrl) {
        setze(kundenUrl);
        log.info("[KUNDENLINK] Kundenadresse: {}", eigene == null ? "(Serveradresse)" : eigene);
    }

    /** Eigene Kundenadresse setzen; null oder leer = keine. Nur http(s), ohne Schraegstrich am Ende. */
    public static void setze(String kundenUrl) {
        String u = kundenUrl == null ? "" : kundenUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        eigene = u.matches("https?://[A-Za-z0-9.-]+(:\\d+)?") ? u : null;
    }

    public static boolean aktiv() {
        return eigene != null;
    }

    /** Anfang aller Kundenlinks: die eigene Adresse, sonst die Serveradresse. */
    public static String basis(String baseUrl) {
        return eigene != null ? eigene : baseUrl;
    }

    /** Pfad der Anmeldeseite einer Karte auf dem Server selbst (fuer Links innerhalb der Seite). */
    public static String anmeldePfad(String cardId) {
        return eigene != null && kurzMoeglich(cardId) ? "/" + cardId : "/karte-neu/" + cardId;
    }

    /** Link zur Anmeldeseite einer Karte, wie ihn der Kunde sieht. */
    public static String anmeldung(String baseUrl, String cardId) {
        return basis(baseUrl) + anmeldePfad(cardId);
    }

    /** Der kurze Pfad gilt nur fuer Karten-IDs, die die Kurz-Route auch annimmt. */
    static boolean kurzMoeglich(String cardId) {
        return cardId != null && cardId.matches(KURZ_MUSTER);
    }

    /** Muss zur Route in LandingController und zur Freigabe in SecurityConfig passen. */
    public static final String KURZ_MUSTER = "CARD-[A-Za-z0-9]+";
}
