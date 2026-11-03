package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.WebhookEreignis;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.repository.WebhookEreignisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Webhooks: Ereignis landet signiert in der Ausgangstabelle, der Laeufer
 * stellt zu, wiederholt mit wachsendem Abstand und gibt nach fuenf
 * Versuchen auf. Einrichten nur mit https und Freischaltung, Geheimnis
 * einmal sichtbar.
 */
class WebhookServiceTest {

    private Shop shop;
    private WebhookEreignisRepository repo;
    private ShopRepository shopRepo;
    private WebhookService dienst;
    private final Instant jetzt = Instant.parse("2026-11-03T10:00:00Z");
    private final List<Object[]> gesendet = new ArrayList<>();   // [url, body, kopf]
    private int antwortStatus = 200;
    private boolean netzfehler = false;

    @BeforeEach
    void setUp() {
        shop = Shop.create("a@b.de", "hash", "Café Mokka", 3);
        shop.setApiFreigeschaltet(true);
        shop.webhookSetzen("https://kasse.example/hook", "geheim");
        repo = mock(WebhookEreignisRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        shopRepo = mock(ShopRepository.class);
        when(shopRepo.findById(shop.getId())).thenReturn(Optional.of(shop));
        when(shopRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        dienst = new WebhookService(repo, shopRepo, new ObjectMapper());
        dienst.sender((url, body, kopf) -> {
            gesendet.add(new Object[]{url, body, kopf});
            if (netzfehler) throw new java.io.IOException("Verbindung abgelehnt");
            return antwortStatus;
        });
        // kasse.example zeigt im Test auf eine oeffentliche Adresse.
        dienst.aufloeser(host -> host.endsWith(".example")
                ? new java.net.InetAddress[]{java.net.InetAddress.getByName("93.184.216.34")}
                : java.net.InetAddress.getAllByName(host));
    }

    private WebhookEreignis melden() {
        dienst.melde(shop, "stamp.added", WebhookService.daten("customerId", "CUST-1", "stamps", 3, "leer", null));
        ArgumentCaptor<WebhookEreignis> c = ArgumentCaptor.forClass(WebhookEreignis.class);
        verify(repo).save(c.capture());
        return c.getValue();
    }

    @Test
    void melde_schreibtJson_nurMitUrl() {
        WebhookEreignis e = melden();
        assertThat(e.getStatus()).isEqualTo(WebhookEreignis.OFFEN);
        assertThat(e.getTyp()).isEqualTo("stamp.added");
        assertThat(e.getShopId()).isEqualTo(shop.getId());
        assertThat(e.getPayload()).contains("\"typ\":\"stamp.added\"").contains("\"customerId\":\"CUST-1\"")
                .contains("\"stamps\":3").doesNotContain("leer");

        shop.webhookEntfernen();
        dienst.melde(shop, "stamp.added", Map.of());
        verify(repo).save(any());   // weiterhin nur das eine
    }

    @Test
    void lauf_stelltZu_mitSignatur() {
        WebhookEreignis e = melden();
        when(repo.findTop50ByStatusAndNaechsterVersuchLessThanEqualOrderByNaechsterVersuchAsc(eq("OFFEN"), any()))
                .thenReturn(List.of(e));

        assertThat(dienst.lauf(jetzt)).isEqualTo(1);
        assertThat(e.getStatus()).isEqualTo(WebhookEreignis.GESENDET);
        assertThat(e.getVersuche()).isEqualTo(1);
        assertThat(e.getAntwort()).isEqualTo("HTTP 200");
        assertThat(gesendet).hasSize(1);
        assertThat(gesendet.get(0)[0]).isEqualTo("https://kasse.example/hook");
        @SuppressWarnings("unchecked")
        Map<String, String> kopf = (Map<String, String>) gesendet.get(0)[2];
        assertThat(kopf).containsEntry("X-Stampit-Event", "stamp.added")
                .containsEntry("X-Stampit-Delivery", e.getId())
                .containsEntry("X-Stampit-Signature", "sha256=" + WebhookService.signatur("geheim", e.getPayload()));
    }

    @Test
    void lauf_fehler_wiederholtMitAbstand_dannAufgeben() {
        WebhookEreignis e = melden();
        when(repo.findTop50ByStatusAndNaechsterVersuchLessThanEqualOrderByNaechsterVersuchAsc(eq("OFFEN"), any()))
                .thenReturn(List.of(e));
        antwortStatus = 500;

        assertThat(dienst.lauf(jetzt)).isZero();
        assertThat(e.getStatus()).isEqualTo(WebhookEreignis.OFFEN);
        assertThat(e.getVersuche()).isEqualTo(1);
        assertThat(e.getNaechsterVersuch()).isEqualTo(jetzt.plus(Duration.ofMinutes(1)));
        assertThat(e.getAntwort()).isEqualTo("HTTP 500");

        netzfehler = true;
        dienst.lauf(jetzt);
        assertThat(e.getVersuche()).isEqualTo(2);
        assertThat(e.getNaechsterVersuch()).isEqualTo(jetzt.plus(Duration.ofMinutes(5)));
        // Kein roher Fehlertext, nur ein knapper Hinweis.
        assertThat(e.getAntwort()).isEqualTo("Fehler: keine Verbindung");

        dienst.lauf(jetzt); dienst.lauf(jetzt);
        assertThat(e.getStatus()).isEqualTo(WebhookEreignis.OFFEN);
        dienst.lauf(jetzt);
        assertThat(e.getVersuche()).isEqualTo(5);
        assertThat(e.getStatus()).isEqualTo(WebhookEreignis.FEHLER);
    }

    @Test
    void zieladresse_nurHttps_keineInternenAdressen() {
        // Schema, Benutzername, unbekannter Host
        assertThatThrownBy(() -> dienst.pruefeZiel("http://kasse.example/hook"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> dienst.pruefeZiel("https://user:pw@kasse.example/hook"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.pruefeZiel("https://nur-pfad"))
                .isInstanceOf(IllegalArgumentException.class);
        dienst.aufloeser(host -> { throw new java.net.UnknownHostException(host); });
        assertThatThrownBy(() -> dienst.pruefeZiel("https://gibt.es.nicht.example/"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unbekannt");

        // Interne Ziele: localhost, private Netze, Link-Local (Cloud-Metadaten), Carrier-NAT, IPv6
        for (String ip : List.of("127.0.0.1", "10.1.2.3", "192.168.0.5", "172.16.4.4", "169.254.169.254",
                "100.64.1.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1")) {
            dienst.aufloeser(host -> new java.net.InetAddress[]{java.net.InetAddress.getByName(ip)});
            assertThatThrownBy(() -> dienst.pruefeZiel("https://kasse.example/hook"))
                    .as(ip).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Interne");
        }
        // Oeffentlich: in Ordnung.
        dienst.aufloeser(host -> new java.net.InetAddress[]{java.net.InetAddress.getByName("93.184.216.34")});
        dienst.pruefeZiel("https://kasse.example/hook");
        dienst.aufloeser(host -> new java.net.InetAddress[]{java.net.InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")});
        dienst.pruefeZiel("https://kasse.example/hook");
    }

    @Test
    void lauf_zielInzwischenIntern_verworfenOhneVersand() {
        WebhookEreignis e = melden();
        when(repo.findTop50ByStatusAndNaechsterVersuchLessThanEqualOrderByNaechsterVersuchAsc(eq("OFFEN"), any()))
                .thenReturn(List.of(e));
        // DNS zeigt jetzt nach innen (Rebinding): kein Aufruf, Ereignis verworfen.
        dienst.aufloeser(host -> new java.net.InetAddress[]{java.net.InetAddress.getByName("10.0.0.7")});
        assertThat(dienst.lauf(jetzt)).isZero();
        assertThat(e.getStatus()).isEqualTo(WebhookEreignis.FEHLER);
        assertThat(e.getAntwort()).startsWith("Adresse nicht erlaubt");
        assertThat(gesendet).isEmpty();
    }

    @Test
    void lauf_webhookInzwischenWeg_verworfen() {
        WebhookEreignis e = melden();
        when(repo.findTop50ByStatusAndNaechsterVersuchLessThanEqualOrderByNaechsterVersuchAsc(eq("OFFEN"), any()))
                .thenReturn(List.of(e));
        shop.webhookEntfernen();
        assertThat(dienst.lauf(jetzt)).isZero();
        assertThat(e.getStatus()).isEqualTo(WebhookEreignis.FEHLER);
        assertThat(gesendet).isEmpty();
    }

    @Test
    void einrichten_httpsUndFreischaltung_geheimnisEinmal() {
        shop.webhookEntfernen();
        assertThatThrownBy(() -> dienst.einrichten(shop.getId(), "http://kasse.example/hook", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> dienst.einrichten(shop.getId(), "https://kasse.example/hook x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.einrichten(shop.getId(), "https://localhost/hook", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Interne");
        shop.setApiFreigeschaltet(false);
        assertThatThrownBy(() -> dienst.einrichten(shop.getId(), "https://kasse.example/hook", false))
                .isInstanceOf(IllegalStateException.class);
        shop.setApiFreigeschaltet(true);

        WebhookService.Stand s1 = dienst.einrichten(shop.getId(), "https://kasse.example/hook", false);
        assertThat(s1.secret()).hasSize(64);
        assertThat(s1.aktiv()).isTrue();
        assertThat(shop.getWebhookSecret()).isEqualTo(s1.secret());

        // Zweites Speichern derselben Adresse: Geheimnis bleibt, wird nicht mehr gezeigt.
        WebhookService.Stand s2 = dienst.einrichten(shop.getId(), "https://kasse.example/hook2", false);
        assertThat(s2.secret()).isNull();
        assertThat(shop.getWebhookSecret()).isEqualTo(s1.secret());
        assertThat(dienst.stand(shop).secret()).isNull();

        WebhookService.Stand s3 = dienst.einrichten(shop.getId(), "https://kasse.example/hook2", true);
        assertThat(s3.secret()).isNotEqualTo(s1.secret());

        assertThat(dienst.entfernen(shop.getId()).aktiv()).isFalse();
        assertThat(shop.getWebhookUrl()).isNull();
    }

    @Test
    void teste_brauchtAdresse_schreibtTestEreignis() {
        shop.webhookEntfernen();
        assertThatThrownBy(() -> dienst.teste(shop.getId())).isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).save(any());
        shop.webhookSetzen("https://kasse.example/hook", "geheim");
        dienst.teste(shop.getId());
        ArgumentCaptor<WebhookEreignis> c = ArgumentCaptor.forClass(WebhookEreignis.class);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getTyp()).isEqualTo("test");
    }

    @Test
    void signatur_hmacSha256() {
        assertThat(WebhookService.signatur("geheim", "{}")).hasSize(64)
                .isEqualTo(WebhookService.signatur("geheim", "{}"))
                .isNotEqualTo(WebhookService.signatur("anders", "{}"));
    }
}
