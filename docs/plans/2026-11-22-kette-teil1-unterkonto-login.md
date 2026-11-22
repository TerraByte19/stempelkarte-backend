# Kette Teil 1: Unterkonto und Login – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine Filiale einer Kette kann sich mit Benutzername und Passwort einloggen und erreicht ausschließlich `/api/filiale/**`; das Hauptkonto verwaltet diese Logins.

**Architecture:** Neue Entität `FilialZugang` mit eigenem Token (`role = "FILIALE"`). `JwtAuthFilter` wertet die Rolle im Token streng aus. Unterkonten bekommen `ROLE_FILIALE` und einen eigenen URL-Bereich; unter `/api/shop/**` bleibt alles bei `ROLE_SHOP`.

**Tech Stack:** Spring Boot 3.5, Java 21, Spring Security, jjwt, JPA (`ddl-auto: update`), JUnit 5, Mockito, AssertJ, MockMvc.

Spec: `docs/specs/2026-11-22-kette-unterkonten-design.md`, Bauteil 1. Dies ist Teil 1 von 8 (Reihenfolge im Spec).

## Global Constraints

- Für bestehende Läden ändert sich nichts. Alte Laden-Tokens (`role = "SHOP"`) gelten weiter.
- Unterkonten nur bei `shop.getTarif() == Tarif.KETTE`.
- Neue Spalten an bestehenden Tabellen nullable (`ddl-auto: update`, kein Flyway).
- Keine E-Mail in diesem Teil.
- Kommentare und Fehlertexte deutsch, wie im Repo. Fehlertexte für Nutzer mit echten Umlauten.
- Paketwurzel: `src/main/java/com/example/stemplekarte/`, Tests: `src/test/java/com/example/stemplekarte/`.
- Tests: `./mvnw -o -q test` (alles) bzw. `./mvnw -o -q test -Dtest=<Klasse>`.
- Committen nur lokal, **nicht pushen**.
- `FilialZugang.shop` ist LAZY und wird im Filter außerhalb einer Transaktion gelesen: im Filter immer die `findWithShop…`-Abfragen (JOIN FETCH) benutzen, sonst wirft der Proxy.

## File Structure

| Datei | Aufgabe |
|---|---|
| `model/ShopOrt.java` (ändern) | Feld `aktiv` (null = aktiv) |
| `model/FilialZugang.java` (neu) | Login einer Filiale |
| `repository/FilialZugangRepository.java` (neu) | Abfragen, mit Laden geladen |
| `security/JwtService.java` (ändern) | Token für Filiale, Version lesen |
| `security/JwtAuthFilter.java` (ändern) | Rolle streng, `FilialPrincipal` |
| `security/SecurityConfig.java` (ändern) | `/api/filiale/**`, Filter-Abhängigkeiten |
| `service/FilialZugangService.java` (neu) | anlegen, einloggen, Passwort, sperren, löschen |
| `controller/AuthController.java` (ändern) | Login mit E-Mail oder Benutzername |
| `controller/FilialeController.java` (neu) | `/api/filiale/ich` |
| `controller/FilialZugangController.java` (neu) | `/api/shop/filial-zugaenge` fürs Hauptkonto |
| `controller/AdminController.java` (ändern) | Logins beim Löschen eines Ladens mitlöschen |

---

### Task 1: Rolle im Token zählt (Filter streng)

**Files:**
- Modify: `security/JwtService.java`
- Modify: `security/JwtAuthFilter.java`
- Test: `security/RollenFilterTest.java` (neu)

**Interfaces:**
- Produces: `JwtService.generateFilialToken(String zugangId, String shopId, int version)`, `JwtService.extractVersion(String token)` → `Integer` (null wenn nicht vorhanden).

- [ ] **Step 1: Failing test schreiben**

`src/test/java/com/example/stemplekarte/security/RollenFilterTest.java`:

```java
package com.example.stemplekarte.security;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nur ein Token mit Rolle SHOP ist der Inhaber. Ein Token mit anderer oder
 * ohne Rolle darf nie als Inhaber durchgehen - auch wenn sein Subject eine
 * Laden-ID ist.
 */
class RollenFilterTest {

    private static final String SECRET = "test-secret-mindestens-32-zeichen-lang-0123456789";

    private JwtService jwt;
    private ShopRepository shops;
    private Shop shop;

    @BeforeEach
    void aufbau() {
        jwt = new JwtService(SECRET);
        shops = mock(ShopRepository.class);
        shop = Shop.create("a@laden.de", "HASH", "Laden A", 3);
        when(shops.findById(shop.getId())).thenReturn(Optional.of(shop));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void aufraeumen() {
        SecurityContextHolder.clearContext();
    }

    private Authentication anmelden(String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        new JwtAuthFilter(jwt, shops).doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private String tokenMitRolle(String rolle) {
        var b = Jwts.builder()
                .subject(shop.getId())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (rolle != null) b.claim("role", rolle);
        return b.signWith(Keys.hmacShaKeyFor(SECRET.getBytes())).compact();
    }

    @Test
    void ladenToken_bleibtInhaber() throws Exception {
        Authentication auth = anmelden(jwt.generateToken(shop.getId(), shop.getEmail()));
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_SHOP");
    }

    @Test
    void tokenOhneRolle_istNiemand() throws Exception {
        assertThat(anmelden(tokenMitRolle(null))).isNull();
    }

    @Test
    void tokenMitUnbekannterRolle_istNiemand() throws Exception {
        assertThat(anmelden(tokenMitRolle("IRGENDWAS"))).isNull();
    }

    @Test
    void filialToken_mitLadenIdAlsSubject_istKeinInhaber() throws Exception {
        // Der gefaehrliche Fall: Subject = Laden-ID, Rolle = FILIALE.
        Authentication auth = anmelden(tokenMitRolle("FILIALE"));
        assertThat(auth).isNull();
    }

    @Test
    void filialToken_traegtVersion() {
        String t = jwt.generateFilialToken("FZ-1", shop.getId(), 4);
        assertThat(jwt.extractRole(t)).isEqualTo("FILIALE");
        assertThat(jwt.extractShopId(t)).isEqualTo("FZ-1");
        assertThat(jwt.extractVersion(t)).isEqualTo(4);
    }
}
```

- [ ] **Step 2: Test laufen lassen, muss scheitern**

Run: `./mvnw -o -q test -Dtest=RollenFilterTest`
Expected: Compile-Fehler (`generateFilialToken`, `extractVersion` fehlen).

- [ ] **Step 3: `JwtService` ergänzen**

In `security/JwtService.java` nach `generateSupportToken(...)` einfügen:

```java
    /**
     * Unterkonto einer Filiale (Tarif Kette). Subject ist die ID des
     * FilialZugangs, nicht die des Ladens: so kann dieser Token nie mit einem
     * Inhaber-Token verwechselt werden. "ver" ist die Token-Version des
     * Zugangs - nach Passwortwechsel oder Sperre zaehlt sie hoch, und alle
     * aelteren Tokens sind sofort ungueltig.
     */
    public String generateFilialToken(String zugangId, String shopId, int version) {
        return Jwts.builder()
                .subject(zugangId)
                .claim("role", "FILIALE")
                .claim("shop", shopId)
                .claim("ver", version)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(key)
                .compact();
    }

    /** Null, wenn der Token keine Version traegt. */
    public Integer extractVersion(String token) {
        return parseClaims(token).get("ver", Integer.class);
    }
```

- [ ] **Step 4: `JwtAuthFilter` streng machen**

In `security/JwtAuthFilter.java` den Block `if (jwtService.isValid(token)) { ... }` (Zeilen 36–59) ersetzen durch:

```java
            if (jwtService.isValid(token)) {
                // Die Rolle im Token entscheidet. Ohne oder mit unbekannter
                // Rolle bleibt die Anfrage anonym - nie "im Zweifel Inhaber".
                String rolle = jwtService.extractRole(token);
                if ("ADMIN".equals(rolle)) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            "admin",
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else if ("SHOP".equals(rolle)) {
                    // filter(isActive): ein vom Admin gesperrter Laden fliegt
                    // sofort raus, nicht erst wenn sein Token ablaeuft.
                    String shopId = jwtService.extractShopId(token);
                    shopRepo.findById(shopId).filter(Shop::isActive).ifPresent(shop -> {
                        var auth = new UsernamePasswordAuthenticationToken(
                                new ShopPrincipal(shop),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_SHOP"))
                        );
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
                }
            }
```

- [ ] **Step 5: Tests laufen lassen**

Run: `./mvnw -o -q test -Dtest=RollenFilterTest,SupportTokenFilterTest`
Expected: PASS (beide Klassen).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/stemplekarte/security/JwtService.java src/main/java/com/example/stemplekarte/security/JwtAuthFilter.java src/test/java/com/example/stemplekarte/security/RollenFilterTest.java
git commit -m "fix(security): Rolle im Token entscheidet, nicht das Subject"
```

---

### Task 2: `ShopOrt.aktiv` und Entität `FilialZugang`

**Files:**
- Modify: `model/ShopOrt.java`
- Create: `model/FilialZugang.java`
- Create: `repository/FilialZugangRepository.java`
- Modify: `controller/AdminController.java` (Laden löschen)
- Test: `model/FilialZugangTest.java`, `repository/FilialZugangLadenTest.java` (neu)

**Interfaces:**
- Produces:
  - `ShopOrt.isAktiv()` → `boolean` (null in der DB = true), `ShopOrt.setAktiv(boolean)`
  - `FilialZugang.create(Shop shop, String benutzername, String passwortHash, String ortId)`
  - `FilialZugang.benutzernameSauber(String roh)` → `String`, wirft `IllegalArgumentException`
  - Getter `getId()`, `getShop()`, `getOrtId()`, `getBenutzername()`, `getPasswortHash()`, `isGesperrt()`, `getTokenVersion()`, `getCreatedAt()`, `getLastLoginAt()`
  - `neuesPasswort(String hash)`, `sperre(boolean)`, `eingeloggt(Instant)`
  - Repository: `findWithShopById(String)`, `findWithShopByBenutzername(String)`, `existsByBenutzername(String)`, `findByShopOrderByCreatedAtAsc(Shop)`, `findByIdAndShop(String, Shop)`, `deleteByShop(Shop)`

- [ ] **Step 1: Failing tests schreiben**

`src/test/java/com/example/stemplekarte/model/FilialZugangTest.java`:

```java
package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilialZugangTest {

    private final Shop shop = Shop.create("z@kette.de", "HASH", "Kette", 3);

    @Test
    void benutzername_wirdKleinUndGetrimmt() {
        assertThat(FilialZugang.benutzernameSauber("  BackBaron-Mitte ")).isEqualTo("backbaron-mitte");
    }

    @Test
    void benutzername_ohneAtUndSonderzeichen() {
        for (String falsch : new String[]{null, "", "ab", "a@b.de", "mit leer", "ümlaut", "-vorne", "x".repeat(41)}) {
            assertThatThrownBy(() -> FilialZugang.benutzernameSauber(falsch))
                    .as(String.valueOf(falsch))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void neuerZugang_istOffenMitVersionNull() {
        FilialZugang z = FilialZugang.create(shop, "backbaron-mitte", "HASH", null);
        assertThat(z.getId()).startsWith("FZ-");
        assertThat(z.isGesperrt()).isFalse();
        assertThat(z.getTokenVersion()).isZero();
        assertThat(z.getOrtId()).isNull();
    }

    @Test
    void passwortUndSperre_zaehlenDieVersionHoch() {
        FilialZugang z = FilialZugang.create(shop, "backbaron-mitte", "HASH", "ORT-1");
        z.neuesPasswort("NEU");
        assertThat(z.getTokenVersion()).isEqualTo(1);
        assertThat(z.getPasswortHash()).isEqualTo("NEU");
        z.sperre(true);
        assertThat(z.getTokenVersion()).isEqualTo(2);
        assertThat(z.isGesperrt()).isTrue();
        z.sperre(false);
        assertThat(z.getTokenVersion()).isEqualTo(3);
        assertThat(z.isGesperrt()).isFalse();
    }

    @Test
    void ort_ohneEintragGiltAlsAktiv() {
        ShopOrt ort = ShopOrt.create(shop, "Nord", 52.5, 13.4);
        assertThat(ort.isAktiv()).isTrue();
        ort.setAktiv(false);
        assertThat(ort.isAktiv()).isFalse();
    }
}
```

`src/test/java/com/example/stemplekarte/repository/FilialZugangLadenTest.java`:

```java
package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Laden am Unterkonto muss mitgeladen sein: der Filter liest ihn
 * ausserhalb jeder Transaktion (gleiche Falle wie StaffTokenLadenTest).
 */
@DataJpaTest
class FilialZugangLadenTest {

    @Autowired private FilialZugangRepository zugaenge;
    @Autowired private ShopRepository shops;
    @Autowired private EntityManager em;

    @Test
    void ladenIstMitgeladen_perIdUndPerBenutzername() {
        Shop shop = shops.save(Shop.create("kette@localhost.test", "hash", "Kette", 5));
        FilialZugang z = zugaenge.save(FilialZugang.create(shop, "kette-mitte", "hash", null));
        em.flush();
        em.clear();

        FilialZugang perId = zugaenge.findWithShopById(z.getId()).orElseThrow();
        assertThat(Hibernate.isInitialized(perId.getShop())).isTrue();
        em.clear();

        FilialZugang perName = zugaenge.findWithShopByBenutzername("kette-mitte").orElseThrow();
        assertThat(Hibernate.isInitialized(perName.getShop())).isTrue();
        assertThat(zugaenge.existsByBenutzername("kette-mitte")).isTrue();
        assertThat(zugaenge.existsByBenutzername("gibt-es-nicht")).isFalse();
    }
}
```

- [ ] **Step 2: Tests laufen lassen, müssen scheitern**

Run: `./mvnw -o -q test -Dtest=FilialZugangTest,FilialZugangLadenTest`
Expected: Compile-Fehler (`FilialZugang` fehlt).

- [ ] **Step 3: `ShopOrt.aktiv`**

In `model/ShopOrt.java` nach dem Feld `createdAt` einfügen:

```java
    // Nur fuer Ketten: eine Filiale kann eingetragen, aber noch nicht
    // eingeschaltet sein. Null = aktiv, damit bestehende Standorte bleiben,
    // wie sie sind.
    @Column
    private Boolean aktiv;
```

und nach `getCreatedAt()`:

```java
    public boolean isAktiv() { return aktiv == null || aktiv; }
    public void setAktiv(boolean aktiv) { this.aktiv = aktiv; }
```

- [ ] **Step 4: Entität `FilialZugang`**

`src/main/java/com/example/stemplekarte/model/FilialZugang.java`:

```java
package com.example.stemplekarte.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Unterkonto einer Filiale (Tarif Kette): Benutzername + Passwort, vom
 * Hauptkonto vergeben. Darf nur /api/filiale/** - scannen, eigene Statistik,
 * eigene Geraete, Stempel korrigieren. Keine E-Mail: ein vergessenes Passwort
 * setzt das Hauptkonto neu.
 *
 * tokenVersion steht im Token (JwtService.generateFilialToken). Jeder
 * Passwortwechsel und jede Sperre zaehlt sie hoch, damit ausgestellte Tokens
 * sofort ungueltig sind.
 */
@Entity
@Table(name = "filial_zugaenge")
public class FilialZugang {

    public static final int NAME_MAX = 40;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{2,39}");
    public static final String NAME_REGEL =
            "Benutzername: 3 bis 40 Zeichen, nur Buchstaben, Ziffern und Bindestrich.";

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    // Null = Hauptstandort. Nur die ID, keine Beziehung (wie StaffToken.ortId).
    @Column(name = "ort_id", length = 64)
    private String ortId;

    @Column(nullable = false, unique = true, length = NAME_MAX)
    private String benutzername;

    @Column(name = "passwort_hash", nullable = false)
    private String passwortHash;

    @Column(nullable = false)
    private boolean gesperrt;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected FilialZugang() {}

    public static FilialZugang create(Shop shop, String benutzername, String passwortHash, String ortId) {
        FilialZugang z = new FilialZugang();
        z.id = "FZ-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        z.shop = shop;
        z.benutzername = benutzernameSauber(benutzername);
        z.passwortHash = passwortHash;
        z.ortId = (ortId == null || ortId.isBlank()) ? null : ortId;
        z.createdAt = Instant.now();
        return z;
    }

    /** Klein, getrimmt, ohne @ - so ist er nie mit einer Laden-E-Mail zu verwechseln. */
    public static String benutzernameSauber(String roh) {
        if (roh == null) throw new IllegalArgumentException(NAME_REGEL);
        String n = roh.trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(n).matches()) throw new IllegalArgumentException(NAME_REGEL);
        return n;
    }

    public String getId() { return id; }
    public Shop getShop() { return shop; }
    public String getOrtId() { return ortId; }
    public String getBenutzername() { return benutzername; }
    public String getPasswortHash() { return passwortHash; }
    public boolean isGesperrt() { return gesperrt; }
    public int getTokenVersion() { return tokenVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }

    public void neuesPasswort(String hash) {
        this.passwortHash = hash;
        this.tokenVersion++;
    }

    public void sperre(boolean gesperrt) {
        this.gesperrt = gesperrt;
        this.tokenVersion++;
    }

    public void eingeloggt(Instant jetzt) { this.lastLoginAt = jetzt; }
}
```

- [ ] **Step 5: Repository**

`src/main/java/com/example/stemplekarte/repository/FilialZugangRepository.java`:

```java
package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FilialZugangRepository extends JpaRepository<FilialZugang, String> {

    // MIT Laden: der JwtAuthFilter liest ausserhalb jeder Transaktion.
    @Query("select z from FilialZugang z join fetch z.shop where z.id = :id")
    Optional<FilialZugang> findWithShopById(@Param("id") String id);

    @Query("select z from FilialZugang z join fetch z.shop where z.benutzername = :benutzername")
    Optional<FilialZugang> findWithShopByBenutzername(@Param("benutzername") String benutzername);

    boolean existsByBenutzername(String benutzername);

    List<FilialZugang> findByShopOrderByCreatedAtAsc(Shop shop);

    Optional<FilialZugang> findByIdAndShop(String id, Shop shop);

    // Beim Loeschen eines Ladens (AdminController#deleteShop).
    void deleteByShop(Shop shop);
}
```

- [ ] **Step 6: Laden löschen räumt Logins mit ab**

In `controller/AdminController.java`:
1. Feld und Konstruktor-Parameter `FilialZugangRepository filialZugaenge` ergänzen, genau nach dem Muster des vorhandenen Feldes `shopOrte` (Import `com.example.stemplekarte.repository.FilialZugangRepository`).
2. In `deleteShop`, direkt nach der Zeile `shopOrte.deleteByShop(shop);` (Zeile 671):

```java
        filialZugaenge.deleteByShop(shop);
```

Ohne diese Zeile scheitert das Löschen einer Kette am Fremdschlüssel.

- [ ] **Step 7: Tests laufen lassen**

Run: `./mvnw -o -q test -Dtest=FilialZugangTest,FilialZugangLadenTest`
Expected: PASS.

Run: `./mvnw -o -q test`
Expected: alles grün (AdminController-Tests bauen den Controller ggf. von Hand: dort den neuen Konstruktor-Parameter als `mock(FilialZugangRepository.class)` nachziehen).

- [ ] **Step 8: Commit**

```bash
git add -A src/main/java/com/example/stemplekarte src/test/java/com/example/stemplekarte
git commit -m "feat(kette): Entitaet FilialZugang und Schalter aktiv am Standort"
```

---

### Task 3: `FilialZugangService`

**Files:**
- Create: `service/FilialZugangService.java`
- Test: `service/FilialZugangServiceTest.java` (neu)

**Interfaces:**
- Consumes: Task 1 `JwtService.generateFilialToken`, Task 2 `FilialZugang`, `FilialZugangRepository`, `ShopOrt.isAktiv()`; vorhanden: `ShopOrtRepository.findByIdAndShop(String, Shop)`, `FilialenService.HAUPT`, `FilialenService.HAUPT_LABEL`, `PasswordEncoder`.
- Produces:
  - `record Anmeldung(String token, FilialZugang zugang, String ortLabel)`
  - `Anmeldung login(String benutzername, String passwort)`
  - `FilialZugang anlegen(Shop shop, String benutzername, String passwort, String ortId)`
  - `List<FilialZugang> liste(Shop shop)`
  - `FilialZugang passwortSetzen(Shop shop, String id, String passwort)`
  - `FilialZugang sperren(Shop shop, String id, boolean gesperrt)`
  - `void loeschen(Shop shop, String id)`
  - `String ortLabel(FilialZugang z)`, `boolean ortAktiv(FilialZugang z)`
  - Konstanten `LOGIN_FALSCH`, `FILIALE_AUS`, `PASSWORT_MIN = 8`

- [ ] **Step 1: Failing test schreiben**

`src/test/java/com/example/stemplekarte/service/FilialZugangServiceTest.java`:

```java
package com.example.stemplekarte.service;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.FilialZugangRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FilialZugangServiceTest {

    private static final String SECRET = "test-secret-mindestens-32-zeichen-lang-0123456789";

    private FilialZugangRepository zugaenge;
    private ShopOrtRepository orte;
    private PasswordEncoder encoder;
    private JwtService jwt;
    private FilialZugangService service;
    private Shop kette;

    @BeforeEach
    void aufbau() {
        zugaenge = mock(FilialZugangRepository.class);
        orte = mock(ShopOrtRepository.class);
        encoder = new BCryptPasswordEncoder(4);
        jwt = new JwtService(SECRET);
        service = new FilialZugangService(zugaenge, orte, encoder, jwt);
        kette = Shop.create("z@kette.de", "HASH", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        when(zugaenge.save(any())).thenAnswer(a -> a.getArgument(0));
    }

    private FilialZugang vorhanden(String name, String passwort, String ortId) {
        FilialZugang z = FilialZugang.create(kette, name, encoder.encode(passwort), ortId);
        when(zugaenge.findWithShopByBenutzername(name)).thenReturn(Optional.of(z));
        when(zugaenge.findByIdAndShop(z.getId(), kette)).thenReturn(Optional.of(z));
        return z;
    }

    @Test
    void anlegen_speichertGehashtesPasswort() {
        FilialZugang z = service.anlegen(kette, "BackBaron-Mitte", "geheim123", null);
        assertThat(z.getBenutzername()).isEqualTo("backbaron-mitte");
        assertThat(encoder.matches("geheim123", z.getPasswortHash())).isTrue();
        assertThat(z.getOrtId()).isNull();
    }

    @Test
    void anlegen_hauptAlsWortIstHauptstandort() {
        assertThat(service.anlegen(kette, "backbaron-haupt", "geheim123", "HAUPT").getOrtId()).isNull();
    }

    @Test
    void anlegen_nurImTarifKette() {
        Shop pro = Shop.create("p@laden.de", "HASH", "Einzelladen", 3);
        assertThatThrownBy(() -> service.anlegen(pro, "laden-eins", "geheim123", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tarif Kette");
    }

    @Test
    void anlegen_fremdeFilialeGibtEsNicht() {
        when(orte.findByIdAndShop("ORT-FREMD", kette)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.anlegen(kette, "backbaron-x", "geheim123", "ORT-FREMD"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void anlegen_kurzesPasswortUndDoppelterName() {
        assertThatThrownBy(() -> service.anlegen(kette, "backbaron-mitte", "kurz", null))
                .isInstanceOf(IllegalArgumentException.class);
        when(zugaenge.existsByBenutzername("backbaron-mitte")).thenReturn(true);
        assertThatThrownBy(() -> service.anlegen(kette, "backbaron-mitte", "geheim123", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("vergeben");
    }

    @Test
    void login_gibtFilialTokenMitVersion() {
        FilialZugang z = vorhanden("backbaron-mitte", "geheim123", null);
        FilialZugangService.Anmeldung a = service.login(" BackBaron-Mitte ", "geheim123");
        assertThat(jwt.extractRole(a.token())).isEqualTo("FILIALE");
        assertThat(jwt.extractShopId(a.token())).isEqualTo(z.getId());
        assertThat(jwt.extractVersion(a.token())).isEqualTo(0);
        assertThat(a.ortLabel()).isEqualTo(FilialenService.HAUPT_LABEL);
        assertThat(z.getLastLoginAt()).isNotNull();
    }

    @Test
    void login_falschesPasswortUndUnbekannterName_gleicherText() {
        vorhanden("backbaron-mitte", "geheim123", null);
        when(zugaenge.findWithShopByBenutzername("gibt-es-nicht")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login("backbaron-mitte", "falsch"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FilialZugangService.LOGIN_FALSCH);
        assertThatThrownBy(() -> service.login("gibt-es-nicht", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FilialZugangService.LOGIN_FALSCH);
        assertThatThrownBy(() -> service.login("a@b.de", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FilialZugangService.LOGIN_FALSCH);
    }

    @Test
    void login_gesperrtLadenAusTarifWeg() {
        FilialZugang z = vorhanden("backbaron-mitte", "geheim123", null);
        z.sperre(true);
        assertThatThrownBy(() -> service.login("backbaron-mitte", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gesperrt");
        z.sperre(false);
        kette.setTarif(Tarif.PRO);
        assertThatThrownBy(() -> service.login("backbaron-mitte", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gesperrt");
        kette.setTarif(Tarif.KETTE);
        kette.setActive(false);
        assertThatThrownBy(() -> service.login("backbaron-mitte", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deaktiviert");
    }

    @Test
    void login_ausgeschalteteFilialeKommtNichtRein() {
        ShopOrt nord = ShopOrt.create(kette, "Nord", 52.5, 13.4);
        nord.setAktiv(false);
        when(orte.findById(nord.getId())).thenReturn(Optional.of(nord));
        vorhanden("backbaron-nord", "geheim123", nord.getId());
        assertThatThrownBy(() -> service.login("backbaron-nord", "geheim123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FilialZugangService.FILIALE_AUS);
        nord.setAktiv(true);
        assertThat(service.login("backbaron-nord", "geheim123").ortLabel()).isEqualTo("Nord");
    }

    @Test
    void passwortSetzen_machtAlteTokensUngueltig() {
        FilialZugang z = vorhanden("backbaron-mitte", "geheim123", null);
        service.passwortSetzen(kette, z.getId(), "ganzneu123");
        assertThat(z.getTokenVersion()).isEqualTo(1);
        assertThat(encoder.matches("ganzneu123", z.getPasswortHash())).isTrue();
    }

    @Test
    void fremderZugang_istNichtDa() {
        when(zugaenge.findByIdAndShop("FZ-FREMD", kette)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.sperren(kette, "FZ-FREMD", true)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.loeschen(kette, "FZ-FREMD")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void loeschen_entferntDenZugang() {
        FilialZugang z = vorhanden("backbaron-mitte", "geheim123", null);
        service.loeschen(kette, z.getId());
        verify(zugaenge).delete(z);
    }
}
```

- [ ] **Step 2: Test laufen lassen, muss scheitern**

Run: `./mvnw -o -q test -Dtest=FilialZugangServiceTest`
Expected: Compile-Fehler (`FilialZugangService` fehlt).

- [ ] **Step 3: Service schreiben**

`src/main/java/com/example/stemplekarte/service/FilialZugangService.java`:

```java
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
    public static final String FILIALE_AUS = "Diese Filiale ist nicht eingeschaltet.";
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

    /** Hauptstandort oder der Name der Filiale; geloeschte Filiale = Hauptstandort-Text. */
    public String ortLabel(FilialZugang z) {
        if (z.getOrtId() == null) return FilialenService.HAUPT_LABEL;
        return orte.findById(z.getOrtId()).map(ShopOrt::getLabel).orElse(FilialenService.GELOESCHT_LABEL);
    }

    /** Hauptstandort ist immer an; eine geloeschte Filiale ist aus. */
    public boolean ortAktiv(FilialZugang z) {
        if (z.getOrtId() == null) return true;
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
```

Hinweis: `FilialenService.GELOESCHT_LABEL` muss `public` oder paket-sichtbar sein (gleiches Paket `service`, also reicht paket-sichtbar). `HAUPT` und `HAUPT_LABEL` ebenso; falls `private`, auf paket-sichtbar stellen.

- [ ] **Step 4: Test laufen lassen**

Run: `./mvnw -o -q test -Dtest=FilialZugangServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/stemplekarte/service/FilialZugangService.java src/test/java/com/example/stemplekarte/service/FilialZugangServiceTest.java src/main/java/com/example/stemplekarte/service/FilialenService.java
git commit -m "feat(kette): FilialZugangService - anlegen, einloggen, Passwort, sperren"
```

---

### Task 4: Filter erkennt Unterkonten, eigener URL-Bereich

**Files:**
- Modify: `security/JwtAuthFilter.java`
- Modify: `security/SecurityConfig.java`
- Test: `security/FilialTokenFilterTest.java` (neu)

**Interfaces:**
- Consumes: Task 1 Token, Task 2 Repository und `ShopOrt.isAktiv()`.
- Produces: `JwtAuthFilter.FilialPrincipal(FilialZugang zugang, Shop shop)`; Konstruktor `JwtAuthFilter(JwtService, ShopRepository, FilialZugangRepository, ShopOrtRepository)`. Der alte Konstruktor mit zwei Parametern bleibt (Unterkonten sind dann aus). Rolle `ROLE_FILIALE`; URL-Regel `/api/filiale/**` → `hasRole("FILIALE")`.

- [ ] **Step 1: Failing test schreiben**

`src/test/java/com/example/stemplekarte/security/FilialTokenFilterTest.java`:

```java
package com.example.stemplekarte.security;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.FilialZugangRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
import com.example.stemplekarte.repository.ShopRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ein Unterkonto ist ROLE_FILIALE und nie ROLE_SHOP. Sperre, Passwortwechsel,
 * Tarifwechsel, gesperrter Laden und ausgeschaltete Filiale wirken sofort.
 */
class FilialTokenFilterTest {

    private static final String SECRET = "test-secret-mindestens-32-zeichen-lang-0123456789";

    private JwtService jwt;
    private ShopRepository shops;
    private FilialZugangRepository zugaenge;
    private ShopOrtRepository orte;
    private Shop kette;
    private FilialZugang zugang;

    @BeforeEach
    void aufbau() {
        jwt = new JwtService(SECRET);
        shops = mock(ShopRepository.class);
        zugaenge = mock(FilialZugangRepository.class);
        orte = mock(ShopOrtRepository.class);
        kette = Shop.create("z@kette.de", "HASH", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        zugang = FilialZugang.create(kette, "backbaron-mitte", "HASH", null);
        when(zugaenge.findWithShopById(zugang.getId())).thenReturn(Optional.of(zugang));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void aufraeumen() {
        SecurityContextHolder.clearContext();
    }

    private String token() {
        return jwt.generateFilialToken(zugang.getId(), kette.getId(), zugang.getTokenVersion());
    }

    private Authentication anmelden(String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        new JwtAuthFilter(jwt, shops, zugaenge, orte)
                .doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void unterkonto_istFilialeUndNieShop() throws Exception {
        Authentication auth = anmelden(token());
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_FILIALE");
        assertThat(auth.getPrincipal()).isInstanceOf(JwtAuthFilter.FilialPrincipal.class);
        JwtAuthFilter.FilialPrincipal p = (JwtAuthFilter.FilialPrincipal) auth.getPrincipal();
        assertThat(p.zugang()).isSameAs(zugang);
        assertThat(p.shop()).isSameAs(kette);
    }

    @Test
    void gesperrt_bleibtDraussen() throws Exception {
        String alt = token();
        zugang.sperre(true);
        assertThat(anmelden(alt)).isNull();
        assertThat(anmelden(token())).isNull();
    }

    @Test
    void altesTokenNachPasswortwechsel_giltNicht() throws Exception {
        String alt = token();
        zugang.neuesPasswort("NEU");
        assertThat(anmelden(alt)).isNull();
        assertThat(anmelden(token())).isNotNull();
    }

    @Test
    void ohneTarifKette_giltNicht() throws Exception {
        kette.setTarif(Tarif.PRO);
        assertThat(anmelden(token())).isNull();
    }

    @Test
    void gesperrterLaden_giltNicht() throws Exception {
        kette.setActive(false);
        assertThat(anmelden(token())).isNull();
    }

    @Test
    void ausgeschalteteFiliale_giltNicht() throws Exception {
        ShopOrt nord = ShopOrt.create(kette, "Nord", 52.5, 13.4);
        when(orte.findById(nord.getId())).thenReturn(Optional.of(nord));
        zugang = FilialZugang.create(kette, "backbaron-nord", "HASH", nord.getId());
        when(zugaenge.findWithShopById(zugang.getId())).thenReturn(Optional.of(zugang));

        assertThat(anmelden(token())).isNotNull();
        SecurityContextHolder.clearContext();
        nord.setAktiv(false);
        assertThat(anmelden(token())).isNull();
    }

    @Test
    void geloeschterZugang_giltNicht() throws Exception {
        String t = token();
        when(zugaenge.findWithShopById(zugang.getId())).thenReturn(Optional.empty());
        assertThat(anmelden(t)).isNull();
    }

    @Test
    void alterKonstruktor_kenntKeineUnterkonten() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token());
        new JwtAuthFilter(jwt, shops).doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
```

- [ ] **Step 2: Test laufen lassen, muss scheitern**

Run: `./mvnw -o -q test -Dtest=FilialTokenFilterTest`
Expected: Compile-Fehler (Konstruktor mit vier Parametern, `FilialPrincipal` fehlen).

- [ ] **Step 3: `JwtAuthFilter` erweitern**

In `security/JwtAuthFilter.java`:

Imports ergänzen:

```java
import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.ShopOrt;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.repository.FilialZugangRepository;
import com.example.stemplekarte.repository.ShopOrtRepository;
```

Felder und Konstruktoren ersetzen durch:

```java
    private final JwtService jwtService;
    private final ShopRepository shopRepo;
    // Beide null = Unterkonten sind aus (alte Aufrufer, Tests).
    private final FilialZugangRepository filialRepo;
    private final ShopOrtRepository ortRepo;

    public JwtAuthFilter(JwtService jwtService, ShopRepository shopRepo) {
        this(jwtService, shopRepo, null, null);
    }

    public JwtAuthFilter(JwtService jwtService, ShopRepository shopRepo,
                         FilialZugangRepository filialRepo, ShopOrtRepository ortRepo) {
        this.jwtService = jwtService;
        this.shopRepo = shopRepo;
        this.filialRepo = filialRepo;
        this.ortRepo = ortRepo;
    }
```

Im Rollen-Block aus Task 1 nach dem `else if ("SHOP".equals(rolle)) { ... }` anhängen:

```java
                else if ("FILIALE".equals(rolle)) {
                    filialeAnmelden(token);
                }
```

Neue Methoden und Record vor der schließenden Klammer der Klasse (neben `ShopPrincipal`):

```java
    /**
     * Unterkonto einer Filiale. Jede Bedingung wird bei JEDER Anfrage neu
     * geprueft: Sperre, Passwortwechsel (Version), Tarifwechsel, gesperrter
     * Laden und ausgeschaltete Filiale wirken sofort, nicht erst wenn der
     * Token ablaeuft.
     */
    private void filialeAnmelden(String token) {
        if (filialRepo == null || ortRepo == null) return;
        Integer version = jwtService.extractVersion(token);
        if (version == null) return;
        filialRepo.findWithShopById(jwtService.extractShopId(token))
                .filter(z -> !z.isGesperrt())
                .filter(z -> z.getTokenVersion() == version)
                .filter(z -> z.getShop().isActive())
                .filter(z -> z.getShop().getTarif() == Tarif.KETTE)
                .filter(this::ortAktiv)
                .ifPresent(z -> {
                    var auth = new UsernamePasswordAuthenticationToken(
                            new FilialPrincipal(z, z.getShop()),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_FILIALE"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
    }

    /** Hauptstandort ist immer an; eine geloeschte Filiale ist aus. */
    private boolean ortAktiv(FilialZugang z) {
        if (z.getOrtId() == null) return true;
        return ortRepo.findById(z.getOrtId()).map(ShopOrt::isAktiv).orElse(false);
    }

    public record FilialPrincipal(FilialZugang zugang, Shop shop) {}
```

- [ ] **Step 4: `SecurityConfig` verdrahten**

In `security/SecurityConfig.java`:

1. Felder und Konstruktor um zwei Repositories erweitern:

```java
    private final com.example.stemplekarte.repository.FilialZugangRepository filialZugangRepo;
    private final com.example.stemplekarte.repository.ShopOrtRepository shopOrtRepo;

    public SecurityConfig(JwtService jwtService, ShopRepository shopRepo,
                          StaffTokenRepository staffTokenRepo,
                          com.example.stemplekarte.repository.ApiKeyRepository apiKeyRepo,
                          com.example.stemplekarte.repository.FilialZugangRepository filialZugangRepo,
                          com.example.stemplekarte.repository.ShopOrtRepository shopOrtRepo) {
        this.jwtService = jwtService;
        this.shopRepo = shopRepo;
        this.staffTokenRepo = staffTokenRepo;
        this.apiKeyRepo = apiKeyRepo;
        this.filialZugangRepo = filialZugangRepo;
        this.shopOrtRepo = shopOrtRepo;
    }
```

2. Direkt **vor** der Zeile `.requestMatchers("/api/shop/**").hasRole("SHOP")`:

```java
                        // Unterkonten der Filialen (Tarif Kette). Eigener Bereich:
                        // unter /api/shop/** bleibt ALLES beim Inhaber.
                        .requestMatchers("/api/filiale/**").hasRole("FILIALE")
```

3. `new JwtAuthFilter(jwtService, shopRepo)` ersetzen durch:

```java
new JwtAuthFilter(jwtService, shopRepo, filialZugangRepo, shopOrtRepo)
```

- [ ] **Step 5: Tests laufen lassen**

Run: `./mvnw -o -q test -Dtest=FilialTokenFilterTest,RollenFilterTest,SupportTokenFilterTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/stemplekarte/security src/test/java/com/example/stemplekarte/security/FilialTokenFilterTest.java
git commit -m "feat(kette): Unterkonto wird ROLE_FILIALE mit eigenem Bereich /api/filiale"
```

---

### Task 5: Login mit Benutzername, `/api/filiale/ich`

**Files:**
- Modify: `controller/AuthController.java`
- Create: `controller/FilialeController.java`
- Test: `controller/FilialLoginTest.java` (neu)

**Interfaces:**
- Consumes: Task 3 `FilialZugangService.login` / `Anmeldung`, Task 4 `JwtAuthFilter.FilialPrincipal`.
- Produces:
  - `POST /api/auth/login` Body unverändert `{email, password}`; `email` darf ein Benutzername sein. Antwort `{token, shopId, name, rolle, ortId, ortLabel}` mit `rolle` = `"SHOP"` oder `"FILIALE"`; `ortId` beim Hauptstandort `"HAUPT"`, beim Inhaber `null`.
  - `GET /api/filiale/ich` → `{kette, benutzername, ortId, ortLabel}`.

- [ ] **Step 1: Failing test schreiben**

`src/test/java/com/example/stemplekarte/controller/FilialLoginTest.java`:

```java
package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.security.LoginAttemptService;
import com.example.stemplekarte.service.FilialZugangService;
import com.example.stemplekarte.service.FilialenService;
import com.example.stemplekarte.service.ShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FilialLoginTest {

    private ShopService shopService;
    private FilialZugangService filialService;
    private LoginAttemptService versuche;
    private AuthController controller;
    private Shop kette;

    @BeforeEach
    void aufbau() {
        shopService = mock(ShopService.class);
        filialService = mock(FilialZugangService.class);
        versuche = mock(LoginAttemptService.class);
        controller = new AuthController(shopService, versuche, filialService);
        kette = Shop.create("z@kette.de", "HASH", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
    }

    @Test
    void mitEmail_laeuftDerLadenLoginWieBisher() {
        when(shopService.login("z@kette.de", "pw")).thenReturn("LADEN-TOKEN");
        when(shopService.getByEmail("z@kette.de")).thenReturn(kette);

        AuthController.AuthResponse r = controller.login(
                new AuthController.LoginRequest("z@kette.de", "pw"), new MockHttpServletRequest());

        assertThat(r.token()).isEqualTo("LADEN-TOKEN");
        assertThat(r.shopId()).isEqualTo(kette.getId());
        assertThat(r.rolle()).isEqualTo("SHOP");
        assertThat(r.ortId()).isNull();
        verifyNoInteractions(filialService);
    }

    @Test
    void ohneAt_istEsEinUnterkonto() {
        FilialZugang z = FilialZugang.create(kette, "backbaron-mitte", "HASH", null);
        when(filialService.login("backbaron-mitte", "pw"))
                .thenReturn(new FilialZugangService.Anmeldung("FILIAL-TOKEN", z, FilialenService.HAUPT_LABEL));

        AuthController.AuthResponse r = controller.login(
                new AuthController.LoginRequest("backbaron-mitte", "pw"), new MockHttpServletRequest());

        assertThat(r.token()).isEqualTo("FILIAL-TOKEN");
        assertThat(r.shopId()).isEqualTo(kette.getId());
        assertThat(r.name()).isEqualTo("BackBaron");
        assertThat(r.rolle()).isEqualTo("FILIALE");
        assertThat(r.ortId()).isEqualTo("HAUPT");
        assertThat(r.ortLabel()).isEqualTo(FilialenService.HAUPT_LABEL);
        verify(versuche).loginSucceeded(anyString());
        verify(shopService, never()).login(anyString(), anyString());
    }

    @Test
    void fehlversuchAmUnterkonto_wirdGezaehlt() {
        when(filialService.login("backbaron-mitte", "falsch"))
                .thenThrow(new IllegalArgumentException(FilialZugangService.LOGIN_FALSCH));
        assertThatThrownBy(() -> controller.login(
                new AuthController.LoginRequest("backbaron-mitte", "falsch"), new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(versuche).loginFailed(anyString());
    }

    @Test
    void ich_zeigtKetteUndFiliale() {
        FilialZugang z = FilialZugang.create(kette, "backbaron-mitte", "HASH", null);
        when(filialService.ortLabel(z)).thenReturn(FilialenService.HAUPT_LABEL);
        var auth = new UsernamePasswordAuthenticationToken(
                new JwtAuthFilter.FilialPrincipal(z, kette), null, List.of());

        Map<String, Object> ich = new FilialeController(filialService).ich(auth);

        assertThat(ich).containsEntry("kette", "BackBaron")
                .containsEntry("benutzername", "backbaron-mitte")
                .containsEntry("ortId", "HAUPT")
                .containsEntry("ortLabel", FilialenService.HAUPT_LABEL);
    }
}
```

- [ ] **Step 2: Test laufen lassen, muss scheitern**

Run: `./mvnw -o -q test -Dtest=FilialLoginTest`
Expected: Compile-Fehler (Konstruktor, `rolle()`, `FilialeController` fehlen).

- [ ] **Step 3: `AuthController` anpassen**

In `controller/AuthController.java`:

Imports ergänzen:

```java
import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.service.FilialZugangService;
import com.example.stemplekarte.service.FilialenService;
```

Felder/Konstruktor:

```java
    private final ShopService shopService;
    private final LoginAttemptService loginAttemptService;
    private final FilialZugangService filialZugangService;

    public AuthController(ShopService shopService, LoginAttemptService loginAttemptService,
                          FilialZugangService filialZugangService) {
        this.shopService = shopService;
        this.loginAttemptService = loginAttemptService;
        this.filialZugangService = filialZugangService;
    }
```

`LoginRequest` und `AuthResponse` ersetzen (das `@Email` fällt weg, weil ein Benutzername erlaubt ist; ob es eine E-Mail ist, entscheidet das `@`):

```java
    // "email" bleibt der Feldname (Frontend), darf aber auch der Benutzername
    // eines Unterkontos sein. Mit @ = Laden, ohne @ = Unterkonto.
    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank String password
    ) {}

    // rolle SHOP oder FILIALE. ortId/ortLabel nur beim Unterkonto
    // (Hauptstandort = "HAUPT").
    public record AuthResponse(String token, String shopId, String name,
                               String rolle, String ortId, String ortLabel) {}
```

Den `try`-Block in `login(...)` ersetzen durch:

```java
        try {
            AuthResponse antwort = req.email().contains("@")
                    ? ladenLogin(req)
                    : filialLogin(req);
            loginAttemptService.loginSucceeded(ip); // Zähler zurücksetzen
            return antwort;
        } catch (Exception e) {
            // Fehlversuch zählen, dann den ursprünglichen Fehler weiterreichen
            loginAttemptService.loginFailed(ip);
            throw e;
        }
```

und zwei private Methoden ergänzen:

```java
    private AuthResponse ladenLogin(LoginRequest req) {
        String token = shopService.login(req.email(), req.password());
        Shop shop = shopService.getByEmail(req.email());
        return new AuthResponse(token, shop.getId(), shop.getName(), "SHOP", null, null);
    }

    private AuthResponse filialLogin(LoginRequest req) {
        FilialZugangService.Anmeldung a = filialZugangService.login(req.email(), req.password());
        FilialZugang z = a.zugang();
        String ortId = z.getOrtId() != null ? z.getOrtId() : FilialenService.HAUPT;
        return new AuthResponse(a.token(), z.getShop().getId(), z.getShop().getName(),
                "FILIALE", ortId, a.ortLabel());
    }
```

`FilialenService.HAUPT` muss von `controller` aus sichtbar sein: falls nicht `public`, auf `public static final` stellen (ebenso `HAUPT_LABEL`).

- [ ] **Step 4: `FilialeController` schreiben**

`src/main/java/com/example/stemplekarte/controller/FilialeController.java`:

```java
package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.FilialZugangService;
import com.example.stemplekarte.service.FilialenService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bereich der Unterkonten (Tarif Kette). Nur ROLE_FILIALE kommt hierher
 * (SecurityConfig). Alles hier ist auf die EIGENE Filiale begrenzt - die
 * Filiale kommt immer aus dem Login, nie aus der Anfrage.
 */
@RestController
@RequestMapping("/api/filiale")
public class FilialeController {

    private final FilialZugangService filialZugangService;

    public FilialeController(FilialZugangService filialZugangService) {
        this.filialZugangService = filialZugangService;
    }

    static JwtAuthFilter.FilialPrincipal filiale(Authentication auth) {
        return (JwtAuthFilter.FilialPrincipal) auth.getPrincipal();
    }

    @Operation(summary = "Wer bin ich: Kette, Benutzername, eigene Filiale")
    @GetMapping("/ich")
    public Map<String, Object> ich(Authentication auth) {
        JwtAuthFilter.FilialPrincipal p = filiale(auth);
        FilialZugang z = p.zugang();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kette", p.shop().getName());
        m.put("benutzername", z.getBenutzername());
        m.put("ortId", z.getOrtId() != null ? z.getOrtId() : FilialenService.HAUPT);
        m.put("ortLabel", filialZugangService.ortLabel(z));
        return m;
    }
}
```

- [ ] **Step 5: Tests laufen lassen**

Run: `./mvnw -o -q test -Dtest=FilialLoginTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/stemplekarte/controller/AuthController.java src/main/java/com/example/stemplekarte/controller/FilialeController.java src/main/java/com/example/stemplekarte/service/FilialenService.java src/test/java/com/example/stemplekarte/controller/FilialLoginTest.java
git commit -m "feat(kette): Login mit Benutzername und /api/filiale/ich"
```

---

### Task 6: Hauptkonto verwaltet die Logins

**Files:**
- Create: `controller/FilialZugangController.java`
- Test: `controller/FilialZugangControllerTest.java` (neu)

**Interfaces:**
- Consumes: Task 3 Service.
- Produces (alle `ROLE_SHOP`, Pfad `/api/shop/filial-zugaenge`):
  - `GET` → Liste von `{id, benutzername, ortId, ortLabel, gesperrt, erstellt, zuletztEingeloggt}`
  - `POST {benutzername, passwort, ortId}` → ein Eintrag
  - `PUT /{id}/passwort {passwort}` → ein Eintrag
  - `PUT /{id}/sperre {gesperrt}` → ein Eintrag
  - `DELETE /{id}` → 204
  - Passwort und Hash stehen in keiner Antwort.

- [ ] **Step 1: Failing test schreiben**

`src/test/java/com/example/stemplekarte/controller/FilialZugangControllerTest.java`:

```java
package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.Tarif;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.FilialZugangService;
import com.example.stemplekarte.service.FilialenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FilialZugangControllerTest {

    private FilialZugangService service;
    private FilialZugangController controller;
    private Shop kette;
    private Authentication auth;
    private FilialZugang zugang;

    @BeforeEach
    void aufbau() {
        service = mock(FilialZugangService.class);
        controller = new FilialZugangController(service);
        kette = Shop.create("z@kette.de", "HASH", "BackBaron", 3);
        kette.setTarif(Tarif.KETTE);
        auth = new UsernamePasswordAuthenticationToken(new JwtAuthFilter.ShopPrincipal(kette), null, List.of());
        zugang = FilialZugang.create(kette, "backbaron-mitte", "GEHEIMER-HASH", null);
        when(service.ortLabel(zugang)).thenReturn(FilialenService.HAUPT_LABEL);
    }

    @Test
    void liste_zeigtKeinPasswort() {
        when(service.liste(kette)).thenReturn(List.of(zugang));
        List<FilialZugangController.Eintrag> liste = controller.liste(auth);
        assertThat(liste).hasSize(1);
        FilialZugangController.Eintrag e = liste.get(0);
        assertThat(e.id()).isEqualTo(zugang.getId());
        assertThat(e.benutzername()).isEqualTo("backbaron-mitte");
        assertThat(e.ortId()).isEqualTo("HAUPT");
        assertThat(e.ortLabel()).isEqualTo(FilialenService.HAUPT_LABEL);
        assertThat(e.gesperrt()).isFalse();
        assertThat(e.toString()).doesNotContain("GEHEIMER-HASH");
    }

    @Test
    void anlegen_reichtAnDenServiceDesEigenenLadens() {
        when(service.anlegen(kette, "backbaron-mitte", "geheim123", "HAUPT")).thenReturn(zugang);
        FilialZugangController.Eintrag e = controller.anlegen(auth,
                new FilialZugangController.NeuRequest("backbaron-mitte", "geheim123", "HAUPT"));
        assertThat(e.benutzername()).isEqualTo("backbaron-mitte");
    }

    @Test
    void passwortSperreLoeschen_nurImEigenenLaden() {
        when(service.passwortSetzen(kette, zugang.getId(), "ganzneu123")).thenReturn(zugang);
        when(service.sperren(kette, zugang.getId(), true)).thenReturn(zugang);

        controller.passwort(auth, zugang.getId(), new FilialZugangController.PasswortRequest("ganzneu123"));
        controller.sperre(auth, zugang.getId(), new FilialZugangController.SperreRequest(true));
        controller.loeschen(auth, zugang.getId());

        verify(service).passwortSetzen(kette, zugang.getId(), "ganzneu123");
        verify(service).sperren(kette, zugang.getId(), true);
        verify(service).loeschen(kette, zugang.getId());
    }
}
```

- [ ] **Step 2: Test laufen lassen, muss scheitern**

Run: `./mvnw -o -q test -Dtest=FilialZugangControllerTest`
Expected: Compile-Fehler (`FilialZugangController` fehlt).

- [ ] **Step 3: Controller schreiben**

`src/main/java/com/example/stemplekarte/controller/FilialZugangController.java`:

```java
package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.FilialZugang;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.service.FilialZugangService;
import com.example.stemplekarte.service.FilialenService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Das Hauptkonto einer Kette verwaltet die Logins seiner Filialen. Zugang
 * wie ShopController ueber das JWT des Inhabers. Passwort und Hash stehen in
 * keiner Antwort.
 */
@RestController
@RequestMapping("/api/shop/filial-zugaenge")
public class FilialZugangController {

    private final FilialZugangService service;

    public FilialZugangController(FilialZugangService service) {
        this.service = service;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    public record NeuRequest(String benutzername, String passwort, String ortId) {}
    public record PasswortRequest(String passwort) {}
    public record SperreRequest(boolean gesperrt) {}

    public record Eintrag(String id, String benutzername, String ortId, String ortLabel,
                          boolean gesperrt, Instant erstellt, Instant zuletztEingeloggt) {}

    private Eintrag eintrag(FilialZugang z) {
        return new Eintrag(z.getId(), z.getBenutzername(),
                z.getOrtId() != null ? z.getOrtId() : FilialenService.HAUPT,
                service.ortLabel(z), z.isGesperrt(), z.getCreatedAt(), z.getLastLoginAt());
    }

    @Operation(summary = "Logins der Filialen (nur Tarif Kette)")
    @GetMapping
    public List<Eintrag> liste(Authentication auth) {
        return service.liste(currentShop(auth)).stream().map(this::eintrag).toList();
    }

    @Operation(summary = "Login für eine Filiale anlegen",
            description = "ortId = ID aus /api/shop/me/orte oder HAUPT. Passwort mindestens 8 Zeichen.")
    @PostMapping
    public Eintrag anlegen(Authentication auth, @RequestBody NeuRequest req) {
        return eintrag(service.anlegen(currentShop(auth), req.benutzername(), req.passwort(), req.ortId()));
    }

    @Operation(summary = "Neues Passwort setzen - alle bisherigen Anmeldungen dieses Logins enden sofort")
    @PutMapping("/{id}/passwort")
    public Eintrag passwort(Authentication auth, @PathVariable String id, @RequestBody PasswortRequest req) {
        return eintrag(service.passwortSetzen(currentShop(auth), id, req.passwort()));
    }

    @Operation(summary = "Login sperren oder wieder freigeben")
    @PutMapping("/{id}/sperre")
    public Eintrag sperre(Authentication auth, @PathVariable String id, @RequestBody SperreRequest req) {
        return eintrag(service.sperren(currentShop(auth), id, req.gesperrt()));
    }

    @Operation(summary = "Login löschen")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> loeschen(Authentication auth, @PathVariable String id) {
        service.loeschen(currentShop(auth), id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Test laufen lassen**

Run: `./mvnw -o -q test -Dtest=FilialZugangControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/stemplekarte/controller/FilialZugangController.java src/test/java/com/example/stemplekarte/controller/FilialZugangControllerTest.java
git commit -m "feat(kette): Hauptkonto verwaltet die Logins seiner Filialen"
```

---

### Task 7: Abschottung durch die echte Filterkette

**Files:**
- Test: `security/UnterkontoAbschottungTest.java` (neu)

**Interfaces:**
- Consumes: alles aus Task 1–6. Kein neuer Produktionscode; scheitert dieser Test, liegt der Fehler in `SecurityConfig`.

- [ ] **Step 1: Test schreiben**

`src/test/java/com/example/stemplekarte/security/UnterkontoAbschottungTest.java`:

```java
package com.example.stemplekarte.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Ein Unterkonto (ROLE_FILIALE) kommt an KEINEN einzigen Endpunkt unter
 * /api/shop. Die Liste kommt aus den echten Controllern: jeder neue Endpunkt
 * ist automatisch mitgeprueft. Umgekehrt kommt der Inhaber nicht in den
 * Bereich der Filialen, und ohne Login kommt dort niemand hin.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnterkontoAbschottungTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    private record Aufruf(HttpMethod methode, String pfad) {}

    private List<Aufruf> endpunkteUnter(String vorsilbe) {
        List<Aufruf> liste = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (info.getPathPatternsCondition() == null) return;
            for (String muster : info.getPathPatternsCondition().getPatternValues()) {
                if (!muster.startsWith(vorsilbe)) continue;
                String pfad = muster.replaceAll("\\{[^}]+}", "x");
                for (RequestMethod m : info.getMethodsCondition().getMethods()) {
                    liste.add(new Aufruf(HttpMethod.valueOf(m.name()), pfad));
                }
            }
        });
        return liste;
    }

    @Test
    void unterkonto_kommtAnKeinenLadenEndpunkt() throws Exception {
        List<Aufruf> alle = endpunkteUnter("/api/shop");
        // Sicherung gegen einen Test, der still nichts prueft.
        assertThat(alle.size()).as("gefundene /api/shop-Endpunkte").isGreaterThan(80);

        for (Aufruf a : alle) {
            int status = mockMvc.perform(request(a.methode(), a.pfad()).with(user("filiale").roles("FILIALE")))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(a.methode() + " " + a.pfad()).isEqualTo(403);
        }
    }

    @Test
    void inhaber_kommtNichtInDenFilialBereich() throws Exception {
        List<Aufruf> alle = endpunkteUnter("/api/filiale");
        assertThat(alle).isNotEmpty();
        for (Aufruf a : alle) {
            int status = mockMvc.perform(request(a.methode(), a.pfad()).with(user("laden").roles("SHOP")))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(a.methode() + " " + a.pfad()).isEqualTo(403);
        }
    }

    @Test
    void ohneLogin_istDerFilialBereichZu() throws Exception {
        for (Aufruf a : endpunkteUnter("/api/filiale")) {
            int status = mockMvc.perform(request(a.methode(), a.pfad()))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(a.methode() + " " + a.pfad()).isIn(401, 403);
        }
    }

    @Test
    void scannerGeraet_kommtNichtInDenFilialBereich() throws Exception {
        for (Aufruf a : endpunkteUnter("/api/filiale")) {
            int status = mockMvc.perform(request(a.methode(), a.pfad()).with(user("geraet").roles("STAFF")))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(a.methode() + " " + a.pfad()).isEqualTo(403);
        }
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./mvnw -o -q test -Dtest=UnterkontoAbschottungTest`
Expected: PASS. Scheitert `unterkonto_kommtAnKeinenLadenEndpunkt` an einem Pfad mit einem anderen Status als 403, ist dieser Pfad in `SecurityConfig` als `permitAll` freigegeben und hat einen Controller: dann **nicht** den Test lockern, sondern melden.

- [ ] **Step 3: Alles laufen lassen**

Run: `./mvnw -o -q test`
Expected: alle Tests grün (vorher 747+, jetzt mehr). Kein Test darf entfallen sein.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/example/stemplekarte/security/UnterkontoAbschottungTest.java
git commit -m "test(kette): Unterkonto erreicht keinen Endpunkt unter /api/shop"
```

---

## Nach Teil 1

Fertig ist: Login für Filialen, strenge Rollen, Verwaltung der Logins durch das Hauptkonto. Noch **nicht** nutzbar im Frontend (kommt in Teil 8).

Nächste Teile laut Spec: 2 Standorte (aktiv schalten, Grenze, Sammel-Anlage) · 3 Statistik je Filiale · 4 Geräte und Korrektur · 5 Wallet-Orte · 6 Mails aus · 7 Admin · 8 Frontend.
