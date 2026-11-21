package com.example.stemplekarte.controller;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.ScanLog;
import com.example.stemplekarte.model.ScanResult;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.service.KundenCookie;
import com.example.stemplekarte.service.QrPayload;
import com.example.stemplekarte.wallet.WalletNotifier;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nagelt fest, wann das Antippen der NFC-Karte stempeln darf und wann nicht.
 *
 * Beim QR-Weg weist sich das Personal mit dem Staff-Token aus - ein Scan ohne
 * Token faellt sofort durch. Der NFC-Weg hat diesen Schutz nicht: der Tag am
 * Tresen traegt eine Adresse, die jedes Handy aufrufen kann. Alles, was
 * zwischen "echter Kunde im Laden" und "jemand ruft die Adresse auf" steht,
 * sind die hier gepruefte Cookie-Signatur und die Abklingzeit. Faellt eines
 * von beiden weg, kann sich jeder beliebig viele Stempel selbst geben.
 */
class NfcStempelTest {

    private static final String SECRET = "test-geheim-mindestens-32-zeichen-lang-ok";
    private static final String TOKEN = "abc123nfctoken";
    private static final String KUNDE = "CUST-1";
    private static final String KARTE = "CARD-1";

    // ── Aufbau ────────────────────────────────────────────────────────────

    private final CardRepository cardRepo = mock(CardRepository.class);
    private final CustomerService service = mock(CustomerService.class);
    private final WalletNotifier notifier = mock(WalletNotifier.class);
    private final ScanLogRepository scanLogRepo = mock(ScanLogRepository.class);

    private final CustomerCard cc = mock(CustomerCard.class);

    private NfcController controller(int abklingSekunden) {
        return controller(abklingSekunden, SECRET);
    }

    private NfcController controller(int abklingSekunden, String secret) {
        AppProperties props = new AppProperties(
                10, "http://localhost:8080", null, null, null,
                new AppProperties.Nfc(true, secret, abklingSekunden));

        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");
        when(shop.getName()).thenReturn("Cafe Test");
        when(shop.isActive()).thenReturn(true);
        // Ohne Ladenort sperrt StandortPflicht das Stempeln - hier geht es
        // aber um Cookie und Abklingzeit, also Ort setzen.
        when(shop.getLatitude()).thenReturn(50.1);
        when(shop.getLongitude()).thenReturn(8.6);

        Card card = mock(Card.class);
        when(card.getId()).thenReturn(KARTE);
        when(card.getShop()).thenReturn(shop);
        when(card.isActive()).thenReturn(true);
        when(card.isPoints()).thenReturn(false);
        when(card.getRewardText()).thenReturn("1 Kaffee gratis");

        when(cardRepo.findByNfcToken(TOKEN)).thenReturn(Optional.of(card));
        when(cardRepo.findByNfcToken(anyString())).thenAnswer(a ->
                TOKEN.equals(a.getArgument(0)) ? Optional.of(card) : Optional.empty());

        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn(KUNDE);
        when(cc.getId()).thenReturn("CC-1");
        when(cc.getCustomer()).thenReturn(customer);
        when(cc.getCard()).thenReturn(card);
        when(cc.getStamps()).thenReturn(3);

        when(service.getOrCreateCustomerCard(KUNDE, KARTE)).thenReturn(cc);
        when(service.processScan(any(QrPayload.class), any(Shop.class), anyInt()))
                .thenReturn(new ScanResult.Stamped(cc, "Stempel hinzugefuegt (3/10)", 0));

        return new NfcController(cardRepo, service, notifier, scanLogRepo, props);
    }

    private MockHttpServletRequest mitCookie(String wert) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(KundenCookie.name(KARTE), wert));
        return req;
    }

    private String echtesCookie() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        KundenCookie.setzen(res, KARTE, KUNDE, SECRET, false);
        String header = res.getHeader("Set-Cookie");
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    void mitGueltigemCookieKommtDerStempelUndDerWalletPush() {
        NfcController c = controller(120);
        when(scanLogRepo.findFirstByCustomerIdAndCardIdOrderByScannedAtDesc(KUNDE, KARTE))
                .thenReturn(Optional.empty());

        ResponseEntity<String> antwort = c.tippen(TOKEN, mitCookie(echtesCookie()),
                new MockHttpServletResponse());

        assertTrue(antwort.getBody().contains("Stempel erhalten"));
        verify(service).processScan(any(QrPayload.class), any(Shop.class), anyInt());
        verify(notifier).nachStempelAenderung(cc);
    }

    @Test
    void ohneCookieGibtEsKeinenStempel() {
        NfcController c = controller(120);

        ResponseEntity<String> antwort = c.tippen(TOKEN, new MockHttpServletRequest(),
                new MockHttpServletResponse());

        assertTrue(antwort.getBody().contains("Noch keine Karte"));
        verify(service, never()).processScan(any(QrPayload.class), any(Shop.class), anyInt());
    }

    /**
     * Cookies sind im Browser frei editierbar. Wer "CUST-FREMD" eintraegt,
     * wuerde sonst auf die Karte eines anderen stempeln - oder, schlimmer,
     * reihum auf alle.
     */
    @Test
    void gefaelschteKundenIdWirdVerworfen() {
        NfcController c = controller(120);

        ResponseEntity<String> antwort = c.tippen(TOKEN,
                mitCookie("CUST-FREMD.AAAAgefaelschteSignatur"),
                new MockHttpServletResponse());

        assertTrue(antwort.getBody().contains("Noch keine Karte"));
        verify(service, never()).processScan(any(QrPayload.class), any(Shop.class), anyInt());
    }

    /**
     * Zweites Antippen direkt hinterher. Fuer Personal und Kunde sieht es
     * aus wie das erste - ohne Abklingzeit waeren es zwei Stempel.
     */
    @Test
    void zweitesAntippenInnerhalbDerAbklingzeitStempeltNicht() {
        NfcController c = controller(120);
        ScanLog vorhin = mock(ScanLog.class);
        when(vorhin.getScannedAt()).thenReturn(Instant.now().minusSeconds(10));
        when(scanLogRepo.findFirstByCustomerIdAndCardIdOrderByScannedAtDesc(KUNDE, KARTE))
                .thenReturn(Optional.of(vorhin));

        ResponseEntity<String> antwort = c.tippen(TOKEN, mitCookie(echtesCookie()),
                new MockHttpServletResponse());

        assertTrue(antwort.getBody().contains("Schon gestempelt"));
        verify(service, never()).processScan(any(QrPayload.class), any(Shop.class), anyInt());
    }

    @Test
    void nachAblaufDerAbklingzeitGehtEsWieder() {
        NfcController c = controller(120);
        ScanLog laengerHer = mock(ScanLog.class);
        when(laengerHer.getScannedAt()).thenReturn(Instant.now().minusSeconds(600));
        when(scanLogRepo.findFirstByCustomerIdAndCardIdOrderByScannedAtDesc(KUNDE, KARTE))
                .thenReturn(Optional.of(laengerHer));

        ResponseEntity<String> antwort = c.tippen(TOKEN, mitCookie(echtesCookie()),
                new MockHttpServletResponse());

        assertTrue(antwort.getBody().contains("Stempel erhalten"));
        verify(service).processScan(any(QrPayload.class), any(Shop.class), anyInt());
    }

    @Test
    void unbekannterTokenStempeltNicht() {
        NfcController c = controller(120);

        ResponseEntity<String> antwort = c.tippen("gibtesnicht", mitCookie(echtesCookie()),
                new MockHttpServletResponse());

        assertTrue(antwort.getBody().contains("Karte unbekannt"));
        verify(service, never()).processScan(any(QrPayload.class), any(Shop.class), anyInt());
    }

    /**
     * Ein stilles Set-Cookie aus einem fremden Bild-Tag wuerde dem Besucher
     * die Kunden-ID des Angreifers unterschieben: ab dann schreibt jedes
     * Antippen am Tresen die Stempel auf dessen Karte. Nur ein echter
     * Seitenaufruf darf die ID setzen.
     */
    @Test
    void nurEchterSeitenaufrufDarfDieKundenIdSetzen() {
        MockHttpServletRequest alsBild = new MockHttpServletRequest();
        alsBild.addHeader("Sec-Fetch-Dest", "image");
        assertFalse(KundenCookie.darfSetzen(alsBild));

        MockHttpServletRequest alsRahmen = new MockHttpServletRequest();
        alsRahmen.addHeader("Sec-Fetch-Dest", "iframe");
        assertFalse(KundenCookie.darfSetzen(alsRahmen));

        MockHttpServletRequest alsSeite = new MockHttpServletRequest();
        alsSeite.addHeader("Sec-Fetch-Dest", "document");
        assertTrue(KundenCookie.darfSetzen(alsSeite));

        // Alte Browser schicken den Kopf nicht - die duerfen weiter.
        assertTrue(KundenCookie.darfSetzen(new MockHttpServletRequest()));
    }

    /**
     * Die Ladenfarbe steht roh in einer CSS-Regel. Ohne Pruefung reicht
     * "red;}body{opacity:0", um die Seite der Kunden umzubauen - dieselbe
     * Luecke, die KartenseiteEscapingTest fuer die Kartenseite festhaelt.
     */
    @Test
    void ladenfarbeKannNichtAusDerCssRegelAusbrechen() {
        NfcController c = controller(120);
        when(scanLogRepo.findFirstByCustomerIdAndCardIdOrderByScannedAtDesc(KUNDE, KARTE))
                .thenReturn(Optional.empty());

        Shop boeser = cc.getCard().getShop();
        when(boeser.getColorBackground()).thenReturn("red;}body{opacity:0");
        when(boeser.getName()).thenReturn("<script>alert(1)</script>");

        String html = c.tippen(TOKEN, mitCookie(echtesCookie()),
                new MockHttpServletResponse()).getBody();

        assertFalse(html.contains("opacity:0"));
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("#3C3489"));
    }

    /** Der Kunden-Name darf nicht im Klartext im Cookie stehen koennen. */
    @Test
    void cookieTraegtKundenIdUndSignatur() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        KundenCookie.setzen(res, KARTE, KUNDE, SECRET, true);
        String header = res.getHeader("Set-Cookie");

        assertTrue(header.contains("HttpOnly"), "ohne HttpOnly liest jedes Skript die Kunden-ID");
        assertTrue(header.contains("SameSite=Lax"), "ohne SameSite schickt Safari das Cookie womoeglich nicht mit");
        assertTrue(header.contains("Secure"));

        MockHttpServletRequest req = mitCookie(echtesCookie());
        assertTrue(KundenCookie.lesen(req, KARTE, SECRET).isPresent());
        assertFalse(KundenCookie.lesen(req, KARTE, "anderes-geheimnis-aber-lang-genug-32").isPresent());
    }
    /**
     * Ohne eigenen Geheimwert bleibt NFC aus. Mit einem bekannten Vorgabewert
     * waere die Signatur des Kunden-Cookies oeffentlich - jeder koennte sich
     * als fremder Kunde ausgeben.
     */
    @Test
    void ohneGeheimwert_istNfcAbgeschaltet() {
        for (String leer : new String[] {"", "   ", null}) {
            NfcController c = controller(120, leer);
            String html = c.tippen(TOKEN, mitCookie(echtesCookie()),
                    new MockHttpServletResponse()).getBody();
            org.assertj.core.api.Assertions.assertThat(html).contains("abgeschaltet");
        }
        verify(service, never()).processScan(any(QrPayload.class), any(Shop.class), anyInt());
    }
    /**
     * Ein Cookie je Karte: die Karte eines anderen Ladens darf die Zuordnung
     * dieses Ladens nicht ueberschreiben oder ersetzen.
     */
    @Test
    void cookieEinerAnderenKarte_zaehltHierNicht() {
        NfcController c = controller(120);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(KundenCookie.name("CARD-ANDERER-LADEN"), echtesCookie()));

        String html = c.tippen(TOKEN, req, new MockHttpServletResponse()).getBody();

        org.assertj.core.api.Assertions.assertThat(html).contains("Noch keine Karte");
        verify(service, never()).processScan(any(QrPayload.class), any(Shop.class), anyInt());
    }

    /**
     * Von einer fremden Seite geschickt darf eine bestehende Zuordnung nicht
     * auf einen anderen Kunden umgestellt werden - sonst sammelt der Absender
     * des Links die Stempel fremder Leute.
     */
    @Test
    void fremdeSeite_darfNichtUmstellen_eigeneUndDirekteAufrufeSchon() {
        MockHttpServletRequest fremd = new MockHttpServletRequest();
        fremd.addHeader("Sec-Fetch-Site", "cross-site");
        assertFalse(KundenCookie.darfWechseln(fremd));

        for (String erlaubt : new String[] {"none", "same-origin", "same-site"}) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("Sec-Fetch-Site", erlaubt);
            assertTrue(KundenCookie.darfWechseln(req), erlaubt);
        }
        // Alter Browser ohne den Kopf: wie bisher.
        assertTrue(KundenCookie.darfWechseln(new MockHttpServletRequest()));
    }

    // ── Zwei Adressen: Serveradresse und Kundenadresse ──────────────────────
    // Das Handy schickt das Kunden-Cookie nur an die Adresse, die es gesetzt
    // hat. Laufen Anmeldung und Tag ueber verschiedene Adressen, kennt der
    // Tag den Kunden nie ("Noch keine Karte" in Dauerschleife).

    @org.junit.jupiter.api.AfterEach
    void kundenadresseZuruecksetzen() {
        com.example.stemplekarte.config.KundenLink.setze(null);
    }

    /** Alte Tags (Serveradresse) schicken Kunden ohne Cookie zur Kundenadresse - dort liegt es. */
    @Test
    void ohneCookie_aufServeradresse_leitetZurKundenadresseWeiter() {
        com.example.stemplekarte.config.KundenLink.setze("https://karte.test");
        NfcController c = controller(120);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("stempelkarte-backend.onrender.com");

        ResponseEntity<String> antwort = c.tippen(TOKEN, req, new MockHttpServletResponse());

        org.assertj.core.api.Assertions.assertThat(antwort.getStatusCode().value()).isEqualTo(302);
        org.assertj.core.api.Assertions.assertThat(antwort.getHeaders().getLocation())
                .hasToString("https://karte.test/t/" + TOKEN + "?weiter=1");
        verify(service, never()).processScan(any(QrPayload.class), any(Shop.class), anyInt());
    }

    /**
     * Schleifenschutz: reicht der Hoster den Hostnamen anders durch als
     * erwartet, saehe jede Anfrage wie "Serveradresse" aus. Die Weiterleitung
     * markiert sich deshalb und passiert hoechstens einmal.
     */
    @Test
    void weiterleitungPassiertHoechstensEinmal() {
        com.example.stemplekarte.config.KundenLink.setze("https://karte.test");
        NfcController c = controller(120);
        MockHttpServletRequest erste = new MockHttpServletRequest();
        erste.setServerName("intern.render");
        ResponseEntity<String> weiter = c.tippen(TOKEN, erste, new MockHttpServletResponse());
        String ziel = weiter.getHeaders().getLocation().toString();
        org.assertj.core.api.Assertions.assertThat(ziel).startsWith("https://karte.test/t/" + TOKEN);

        MockHttpServletRequest zweite = new MockHttpServletRequest();
        zweite.setServerName("intern.render");
        zweite.setQueryString(ziel.substring(ziel.indexOf('?') + 1));
        zweite.setParameter("weiter", "1");

        ResponseEntity<String> antwort = c.tippen(TOKEN, zweite, new MockHttpServletResponse());

        org.assertj.core.api.Assertions.assertThat(antwort.getStatusCode().value()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(antwort.getBody()).contains("Noch keine Karte");
    }

    /** Auf der Kundenadresse selbst: keine Weiterleitung (sonst Schleife), Anlegen bleibt dort. */
    @Test
    void ohneCookie_aufKundenadresse_anlegenKnopfBleibtAufKundenadresse() {
        com.example.stemplekarte.config.KundenLink.setze("https://karte.test");
        NfcController c = controller(120);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("karte.test");

        String html = c.tippen(TOKEN, req, new MockHttpServletResponse()).getBody();

        org.assertj.core.api.Assertions.assertThat(html).contains("Noch keine Karte");
        org.assertj.core.api.Assertions.assertThat(html).contains("href='https://karte.test/" + KARTE + "'");
        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("localhost");
    }

    /** Wer sein Cookie noch von der Serveradresse hat (alte Kunden, alte Tags), stempelt dort weiter. */
    @Test
    void mitCookie_aufServeradresse_stempeltOhneWeiterleitung() {
        com.example.stemplekarte.config.KundenLink.setze("https://karte.test");
        NfcController c = controller(120);
        when(scanLogRepo.findFirstByCustomerIdAndCardIdOrderByScannedAtDesc(KUNDE, KARTE))
                .thenReturn(Optional.empty());
        MockHttpServletRequest req = mitCookie(echtesCookie());
        req.setServerName("stempelkarte-backend.onrender.com");

        ResponseEntity<String> antwort = c.tippen(TOKEN, req, new MockHttpServletResponse());

        org.assertj.core.api.Assertions.assertThat(antwort.getBody()).contains("Stempel erhalten");
    }

    @Test
    void cookieNameTraegtDieKarteUndNurHarmloseZeichen() {
        org.assertj.core.api.Assertions.assertThat(KundenCookie.name("CARD-AB12")).isEqualTo("sk_kunde_CARD-AB12");
        org.assertj.core.api.Assertions.assertThat(KundenCookie.name("CARD;=x y")).isEqualTo("sk_kunde_CARDxy");
    }
}
