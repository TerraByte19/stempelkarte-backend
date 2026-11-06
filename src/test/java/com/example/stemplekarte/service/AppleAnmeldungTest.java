package com.example.stemplekarte.service;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Anmeldung mit Apple: Token-Pruefung ohne Netz mit eigenem Schluesselpaar, JWKS-Parsen, Drossel. */
class AppleAnmeldungTest {

    private static final String CLIENT = "de.stampit.web";
    private static final String KID = "k1";
    private static final KeyPair APPLE = paar();
    private static final KeyPair FREMD = paar();
    private static final Instant JETZT = Instant.now();

    private static KeyPair paar() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Antwortet statt Apple mit festen Schluesseln (Map leer = Laden fehlgeschlagen). */
    private static AppleAnmeldung mitSchluessel(String clientId, Map<String, PublicKey> keys) {
        return new AppleAnmeldung(clientId) {
            @Override
            protected Map<String, PublicKey> laden() {
                return keys;
            }
        };
    }

    private static AppleAnmeldung standard() {
        return mitSchluessel(CLIENT, Map.of(KID, APPLE.getPublic()));
    }

    private static String token(String iss, String aud, Object verified, String email, Instant exp,
                                PrivateKey key, String kid) {
        JwtBuilder b = Jwts.builder()
                .header().keyId(kid).and()
                .issuer(iss)
                .audience().add(aud).and()
                .subject("001234.abcdef")
                .issuedAt(Date.from(JETZT.minusSeconds(5)))
                .expiration(Date.from(exp));
        if (email != null) b.claim("email", email);
        if (verified != null) b.claim("email_verified", verified);
        return b.signWith(key, Jwts.SIG.RS256).compact();
    }

    private static String gut(Object verified) {
        return token(AppleAnmeldung.HERAUSGEBER, CLIENT, verified, "ayse@example.com",
                JETZT.plus(Duration.ofMinutes(10)), APPLE.getPrivate(), KID);
    }

    @Test
    void ohneClientId_inaktiv() {
        AppleAnmeldung a = new AppleAnmeldung("  ");
        assertThat(a.aktiv()).isFalse();
        assertThatThrownBy(() -> a.pruefe("x", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gueltigesToken_liefertProfil() {
        AppleAnmeldung a = standard();
        assertThat(a.aktiv()).isTrue();

        AppleAnmeldung.Profil p = a.pruefe(gut("true"), "Ayşe Yılmaz");
        assertThat(p.email()).isEqualTo("ayse@example.com");
        assertThat(p.name()).isEqualTo("Ayşe Yılmaz");

        // Apple schickt email_verified mal als Text, mal als Wahrheitswert.
        assertThat(a.pruefe(gut(Boolean.TRUE), "Mo").name()).isEqualTo("Mo");
    }

    @Test
    void nameFehlt_dannAusDerAdresse_undBereinigt() {
        AppleAnmeldung a = standard();
        assertThat(a.pruefe(gut("true"), null).name()).isEqualTo("ayse");
        assertThat(a.pruefe(gut("true"), "  ").name()).isEqualTo("ayse");
        assertThat(a.pruefe(gut("true"), "Ay\u0007şe   Y").name()).isEqualTo("Ay şe Y");
        assertThat(a.pruefe(gut("true"), "x".repeat(300)).name()).hasSize(AppleAnmeldung.NAME_MAX);
    }

    @Test
    void abgelehnt_fremdeZielgruppe_Herausgeber_abgelaufen_fremderSchluessel() {
        AppleAnmeldung a = standard();
        Instant spaeter = JETZT.plus(Duration.ofMinutes(10));

        assertThatThrownBy(() -> a.pruefe(token(AppleAnmeldung.HERAUSGEBER, "andere.app", "true",
                "ayse@example.com", spaeter, APPLE.getPrivate(), KID), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(AppleAnmeldung.FEHLER);
        assertThatThrownBy(() -> a.pruefe(token("https://accounts.google.com", CLIENT, "true",
                "ayse@example.com", spaeter, APPLE.getPrivate(), KID), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(AppleAnmeldung.FEHLER);
        assertThatThrownBy(() -> a.pruefe(token(AppleAnmeldung.HERAUSGEBER, CLIENT, "true",
                "ayse@example.com", JETZT.minus(Duration.ofMinutes(5)), APPLE.getPrivate(), KID), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(AppleAnmeldung.FEHLER);
        // Richtiger kid, aber mit fremdem Schluessel unterschrieben.
        assertThatThrownBy(() -> a.pruefe(token(AppleAnmeldung.HERAUSGEBER, CLIENT, "true",
                "ayse@example.com", spaeter, FREMD.getPrivate(), KID), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(AppleAnmeldung.FEHLER);
        // Unbekannter kid.
        assertThatThrownBy(() -> a.pruefe(token(AppleAnmeldung.HERAUSGEBER, CLIENT, "true",
                "ayse@example.com", spaeter, APPLE.getPrivate(), "k9"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(AppleAnmeldung.FEHLER);
        // Kein Schluessel ladbar.
        assertThatThrownBy(() -> mitSchluessel(CLIENT, Map.of()).pruefe(gut("true"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(AppleAnmeldung.FEHLER);
        // Muell.
        assertThatThrownBy(() -> a.pruefe("", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.pruefe("kein.jwt", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.pruefe("x".repeat(5000), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nichtBestaetigt_oderOhneAdresse_abgelehnt() {
        AppleAnmeldung a = standard();
        assertThatThrownBy(() -> a.pruefe(gut("false"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nicht bestätigt");
        assertThatThrownBy(() -> a.pruefe(gut(null), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nicht bestätigt");
        assertThatThrownBy(() -> a.pruefe(token(AppleAnmeldung.HERAUSGEBER, CLIENT, "true", null,
                JETZT.plus(Duration.ofMinutes(10)), APPLE.getPrivate(), KID), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(AppleAnmeldung.FEHLER);
    }

    private static String b64(BigInteger i) {
        byte[] b = i.toByteArray();
        if (b[0] == 0) b = Arrays.copyOfRange(b, 1, b.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Test
    void schluesselAusApplesJwks() throws Exception {
        RSAPublicKey pub = (RSAPublicKey) APPLE.getPublic();
        String json = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + KID + "\",\"use\":\"sig\",\"alg\":\"RS256\","
                + "\"n\":\"" + b64(pub.getModulus()) + "\",\"e\":\"" + b64(pub.getPublicExponent()) + "\"},"
                + "{\"kty\":\"EC\",\"kid\":\"ec1\"},{\"kty\":\"RSA\"}]}";
        Map<String, PublicKey> m = AppleAnmeldung.parse(json);
        assertThat(m).containsOnlyKeys(KID);
        assertThat(m.get(KID)).isEqualTo(pub);

        // Mit dem geparsten Schluessel geht ein echtes Token durch.
        AppleAnmeldung a = mitSchluessel(CLIENT, m);
        assertThat(a.pruefe(gut("true"), "Mo").email()).isEqualTo("ayse@example.com");
    }

    @Test
    void drossel_jeAdresse() {
        Drossel d = new Drossel(3, Duration.ofMinutes(10));
        d.pruefe("1.2.3.4", JETZT);
        d.pruefe("1.2.3.4", JETZT);
        d.pruefe("1.2.3.4", JETZT);
        assertThatThrownBy(() -> d.pruefe("1.2.3.4", JETZT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(Drossel.TEXT);
        assertThatCode(() -> d.pruefe("5.6.7.8", JETZT)).doesNotThrowAnyException();
        assertThatCode(() -> d.pruefe("1.2.3.4", JETZT.plus(Duration.ofMinutes(11)))).doesNotThrowAnyException();
        assertThatCode(() -> d.pruefe(null, JETZT)).doesNotThrowAnyException();

        AppleAnmeldung a = standard();
        for (int i = 0; i < AppleAnmeldung.MAX_JE_IP; i++) a.drossel("9.9.9.9", JETZT);
        assertThatThrownBy(() -> a.drossel("9.9.9.9", JETZT)).isInstanceOf(IllegalArgumentException.class);
    }
}
