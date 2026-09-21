# Punktekarte, Teil 1: Backend-Kern — Umsetzungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Das Backend kann Punktekarten anlegen, Punkte nach Umsatz buchen, Prämien aus einem Katalog einlösen, korrigieren und zurücknehmen — vollständig über die API bedienbar, ohne dass sich für Stempelkarten irgendetwas ändert.

**Architecture:** `Card` bekommt einen Typ (`STAMP`/`POINTS`). Punktestände liegen als Hundertstel-Ganzzahlen auf `CustomerCard`. Jede Bewegung wird als `PointsBooking` geschrieben, aus der Korrektur, Rücknahme und Statistik gemeinsam fallen. Ein reiner Rechenkern (`PointsMath`) ist von der Persistenz getrennt und dadurch ohne Spring testbar.

**Tech Stack:** Spring Boot 3.5, Java 21, Maven, JPA/Hibernate mit `ddl-auto: update`, H2 im Test / Postgres in Produktion, JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/specs/2026-09-21-punktekarte-design.md`

## Global Constraints

- **Der Stempel-Pfad wird nicht verändert.** `CustomerCard.stamps`, `redeemReward`, `processScan` und `/api/scan` bleiben in Verhalten und Ausgabe identisch. Einzige Ausnahme: die mechanische QR-Parse-Extraktion in Task 2, abgesichert durch einen neuen Test.
- **Neue Spalten sind nullable oder haben einen Default**, sonst scheitert `ddl-auto: update` an bestehenden Zeilen in Postgres.
- **`open-in-view: false`.** Lazy-Beziehungen ausserhalb einer Transaktion fliegen um die Ohren. Wer mehr als die Entity selbst braucht, setzt `@EntityGraph` oder arbeitet innerhalb `@Transactional`.
- **Kommentare auf Deutsch, sie erklären das Warum**, nicht das Was. Umlaute in Code-Kommentaren umschrieben (ae, oe, ue), in Texten für Nutzer NICHT.
- **Tests tragen deutsche Namen**, im Klassenkommentar steht, welches Verhalten sie festnageln und warum es kaputt wäre.
- **Keine Geviertstriche** in Texten, die Nutzer sehen. Normale Bindestriche.
- **Vor jedem Push `./mvnw -o test`.** Render baut mit `-DskipTests` — was hier rot ist, geht trotzdem live.
- **Nicht pushen, ohne dass danach gefragt wurde.**
- Grenzen, überall geprüft: `amountCents` 1 bis 9 999 999, `pointsPerEuroX100` 1 bis 100 000, höchstens 20 Prämien je Katalog, Prämienname höchstens 40 Zeichen.

---

### Task 1: Rechenkern `PointsMath`

Reine Rechnung, keine Datenbank, kein Spring. Zuerst, weil alles andere darauf aufbaut und weil sich hier jeder Rundungsfall billig festnageln lässt.

**Files:**
- Create: `src/main/java/com/example/stemplekarte/model/CardType.java`
- Create: `src/main/java/com/example/stemplekarte/model/PointsRounding.java`
- Create: `src/main/java/com/example/stemplekarte/service/PointsMath.java`
- Test: `src/test/java/com/example/stemplekarte/service/PunkteRechnungTest.java`

**Interfaces:**
- Consumes: nichts.
- Produces:
  - `enum CardType { STAMP, POINTS }`
  - `enum PointsRounding { GENAU, ABRUNDEN, KAUFMAENNISCH }`
  - `PointsMath.punkteFuer(long amountCents, int pointsPerEuroX100, PointsRounding rounding) -> long` (Ergebnis in Hundertstel-Punkten)
  - `PointsMath.MAX_AMOUNT_CENTS = 9_999_999L`
  - `PointsMath.MAX_POINTS_PER_EURO_X100 = 100_000`
  - `PointsMath.formatiere(long pointsX100) -> String` (Anzeige, z.B. `"5,2"`)

- [ ] **Step 1: Die beiden Enums anlegen**

`src/main/java/com/example/stemplekarte/model/CardType.java`:

```java
package com.example.stemplekarte.model;

/**
 * Was eine Karte zaehlt. Beim Anlegen gewaehlt, danach fest - ein Wechsel
 * wuerde bestehende Staende bedeutungslos machen (7 Stempel sind keine
 * 7 Punkte).
 */
public enum CardType {
    STAMP,
    POINTS
}
```

`src/main/java/com/example/stemplekarte/model/PointsRounding.java`:

```java
package com.example.stemplekarte.model;

/**
 * Wie das Rechenergebnis gerundet wird. Entscheidet der Laden, nicht der
 * Entwickler: ein Kiosk will krumme Punkte, eine Baeckerei lieber ganze.
 */
public enum PointsRounding {
    /** Zwei Nachkommastellen, nichts verfaellt. 5,20 Euro -> 5,2 Punkte. */
    GENAU,
    /** Auf ganze Punkte ab. 5,70 Euro -> 5 Punkte. */
    ABRUNDEN,
    /** Auf ganze Punkte, ab der Haelfte auf. 5,70 Euro -> 6 Punkte. */
    KAUFMAENNISCH
}
```

- [ ] **Step 2: Den fehlschlagenden Test schreiben**

`src/test/java/com/example/stemplekarte/service/PunkteRechnungTest.java`:

```java
package com.example.stemplekarte.service;

import com.example.stemplekarte.model.PointsRounding;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nagelt die Umrechnung Euro -> Punkte fest.
 *
 * Der Kurs liegt als EINE Ganzzahl vor (Punkte pro Euro, mal 100), damit
 * "5 Punkte pro Euro" und "1 Punkt pro 5 Euro" dasselbe Feld benutzen und
 * kein double ins Spiel kommt. Ein double zeigt sonst irgendwann 5,199999
 * auf der Wallet-Karte.
 *
 * Alle Ergebnisse sind Hundertstel-Punkte: 520 bedeutet 5,20 Punkte.
 */
class PunkteRechnungTest {

    @Test
    void einEuroEinPunkt_genau() {
        // 5,20 Euro bei 1 Euro = 1 Punkt
        assertThat(PointsMath.punkteFuer(520, 100, PointsRounding.GENAU)).isEqualTo(520);
    }

    @Test
    void einEuroFuenfPunkte_genau() {
        assertThat(PointsMath.punkteFuer(520, 500, PointsRounding.GENAU)).isEqualTo(2600);
    }

    @Test
    void fuenfEuroEinPunkt_genau() {
        assertThat(PointsMath.punkteFuer(520, 20, PointsRounding.GENAU)).isEqualTo(104);
    }

    @Test
    void zweiEuroDreiPunkte_genau() {
        assertThat(PointsMath.punkteFuer(520, 150, PointsRounding.GENAU)).isEqualTo(780);
    }

    @Test
    void abrunden_schneidetAufGanzePunkte() {
        // 5,70 Punkte -> 5
        assertThat(PointsMath.punkteFuer(570, 100, PointsRounding.ABRUNDEN)).isEqualTo(500);
    }

    @Test
    void kaufmaennisch_rundetAbDerHaelfteAuf() {
        // 5,70 -> 6
        assertThat(PointsMath.punkteFuer(570, 100, PointsRounding.KAUFMAENNISCH)).isEqualTo(600);
        // 5,20 -> 5
        assertThat(PointsMath.punkteFuer(520, 100, PointsRounding.KAUFMAENNISCH)).isEqualTo(500);
        // genau 5,50 -> 6 (ab der Haelfte auf)
        assertThat(PointsMath.punkteFuer(550, 100, PointsRounding.KAUFMAENNISCH)).isEqualTo(600);
    }

    @Test
    void negativerBetrag_rundetSymmetrisch() {
        // Korrekturbuchung: -5,70 Euro darf nicht anders runden als +5,70.
        // Javas Division schneidet Richtung Null ab - ohne Vorzeichen-
        // behandlung waere -5,70 kaufmaennisch faelschlich -5 statt -6.
        assertThat(PointsMath.punkteFuer(-570, 100, PointsRounding.KAUFMAENNISCH)).isEqualTo(-600);
        assertThat(PointsMath.punkteFuer(-570, 100, PointsRounding.ABRUNDEN)).isEqualTo(-500);
    }

    @Test
    void grossterErlaubterFall_laeuftNichtUeber() {
        long ergebnis = PointsMath.punkteFuer(
                PointsMath.MAX_AMOUNT_CENTS, PointsMath.MAX_POINTS_PER_EURO_X100,
                PointsRounding.GENAU);
        // 99 999,99 Euro mal 1000 Punkte pro Euro = 99 999 990 Punkte
        assertThat(ergebnis).isEqualTo(9_999_999_000L);
        assertThat(ergebnis).isPositive();
    }

    @Test
    void betragUeberGrenze_wirdAbgelehnt() {
        assertThatThrownBy(() -> PointsMath.punkteFuer(
                PointsMath.MAX_AMOUNT_CENTS + 1, 100, PointsRounding.GENAU))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Betrag");
    }

    @Test
    void kursUeberGrenze_wirdAbgelehnt() {
        assertThatThrownBy(() -> PointsMath.punkteFuer(
                100, PointsMath.MAX_POINTS_PER_EURO_X100 + 1, PointsRounding.GENAU))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kurs");
    }

    @Test
    void anzeige_schneidetNachlaufendeNullenAb() {
        assertThat(PointsMath.formatiere(520)).isEqualTo("5,2");
        assertThat(PointsMath.formatiere(2600)).isEqualTo("26");
        assertThat(PointsMath.formatiere(104)).isEqualTo("1,04");
        assertThat(PointsMath.formatiere(0)).isEqualTo("0");
    }
}
```

- [ ] **Step 3: Test laufen lassen, Fehlschlag bestätigen**

Run: `./mvnw -o test -Dtest=PunkteRechnungTest`
Expected: FAIL, Übersetzungsfehler `cannot find symbol: class PointsMath`

- [ ] **Step 4: `PointsMath` implementieren**

`src/main/java/com/example/stemplekarte/service/PointsMath.java`:

```java
package com.example.stemplekarte.service;

import com.example.stemplekarte.model.PointsRounding;

/**
 * Umrechnung Einkaufsbetrag -> Punkte. Bewusst ohne Spring und ohne
 * Datenbank, damit jeder Rundungsfall billig festzunageln ist.
 *
 * Alles laeuft in long. Kein double: ein double zeigt nach ein paar
 * Buchungen 5,199999 auf der Wallet-Karte. Kein BigDecimal: fuer eine
 * Multiplikation und eine Division ist das Zeremonie.
 *
 * Einheiten, die durchgaengig gelten:
 * - amountCents        = Betrag in Cent (520 = 5,20 Euro)
 * - pointsPerEuroX100  = Punkte pro Euro, mal 100 (100 = 1 Punkt pro Euro)
 * - Rueckgabe          = Punkte mal 100 (520 = 5,20 Punkte)
 */
public final class PointsMath {

    /** 99 999,99 Euro. Reicht fuer jeden Ladenbon und haelt das Produkt klein. */
    public static final long MAX_AMOUNT_CENTS = 9_999_999L;

    /** 1000 Punkte pro Euro. Darueber wird die Zahl auf der Karte unlesbar. */
    public static final int MAX_POINTS_PER_EURO_X100 = 100_000;

    private PointsMath() {}

    /**
     * Rechnet einen Betrag in Hundertstel-Punkte um.
     *
     * Die Formel ist pointsX100 = amountCents * pointsPerEuroX100 / 100.
     * Gerechnet wird auf dem Betrag OHNE Vorzeichen, das Vorzeichen kommt am
     * Ende zurueck: Javas Division schneidet Richtung Null ab, damit wuerde
     * eine Korrekturbuchung ueber -5,70 Euro kaufmaennisch auf -5 statt -6
     * runden und sich anders verhalten als die Buchung, die sie zuruecknimmt.
     */
    public static long punkteFuer(long amountCents, int pointsPerEuroX100,
                                  PointsRounding rounding) {
        if (Math.abs(amountCents) > MAX_AMOUNT_CENTS) {
            throw new IllegalArgumentException(
                    "Betrag ausserhalb der Grenze (hoechstens 99.999,99 Euro)");
        }
        if (pointsPerEuroX100 < 1 || pointsPerEuroX100 > MAX_POINTS_PER_EURO_X100) {
            throw new IllegalArgumentException(
                    "Kurs ausserhalb der Grenze (1 bis 100.000)");
        }

        long vorzeichen = amountCents < 0 ? -1 : 1;
        long betrag = Math.abs(amountCents);

        // Zaehler traegt zwei Stellen mehr als das Ergebnis: er ist
        // pointsX100 * 100. Auf dieser Zwischenstufe wird gerundet.
        long zaehler = betrag * pointsPerEuroX100;

        long ergebnis = switch (rounding) {
            // Auf das naechste Hundertstel Punkt, ab der Haelfte auf.
            case GENAU -> (zaehler + 50) / 100;
            // Auf ganze Punkte ab, danach wieder in Hundertstel.
            case ABRUNDEN -> (zaehler / 10_000) * 100;
            // Auf ganze Punkte, ab der Haelfte auf.
            case KAUFMAENNISCH -> ((zaehler + 5_000) / 10_000) * 100;
        };

        return vorzeichen * ergebnis;
    }

    /**
     * Punkte fuer die Anzeige: hoechstens zwei Nachkommastellen, nachlaufende
     * Nullen weg. 520 wird "5,2", 2600 wird "26", 104 wird "1,04".
     *
     * Komma statt Punkt, weil die Oberflaechen deutsch und arabisch sind und
     * beide das Komma als Dezimaltrenner setzen.
     */
    public static String formatiere(long pointsX100) {
        long ganz = pointsX100 / 100;
        long rest = Math.abs(pointsX100 % 100);
        if (rest == 0) return String.valueOf(ganz);
        String nachkomma = rest % 10 == 0
                ? String.valueOf(rest / 10)
                : (rest < 10 ? "0" + rest : String.valueOf(rest));
        return ganz + "," + nachkomma;
    }
}
```

- [ ] **Step 5: Test laufen lassen, grün bestätigen**

Run: `./mvnw -o test -Dtest=PunkteRechnungTest`
Expected: PASS, 11 Tests

- [ ] **Step 6: Volle Suite laufen lassen**

Run: `./mvnw -o test`
Expected: PASS, die zehn bestehenden Testklassen unverändert grün

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/stemplekarte/model/CardType.java \
        src/main/java/com/example/stemplekarte/model/PointsRounding.java \
        src/main/java/com/example/stemplekarte/service/PointsMath.java \
        src/test/java/com/example/stemplekarte/service/PunkteRechnungTest.java
git commit -m "feat: Rechenkern fuer die Punktekarte (Euro nach Punkten)"
```

---

### Task 2: QR-Parsen an einer Stelle

`CustomerService` parst den QR-Inhalt heute zweimal wortgleich (`processScan`, `resetCard`). Der Punkte-Dienst braucht dasselbe ein drittes Mal. Vorher an eine Stelle ziehen, statt die Kopie zu vervielfachen.

Rein mechanisch, kein Verhaltenswechsel. Der neue Test hält das fest.

**Files:**
- Create: `src/main/java/com/example/stemplekarte/service/QrPayload.java`
- Modify: `src/main/java/com/example/stemplekarte/service/CustomerService.java` (Zeilen 149-161 und 217-229, beide Parse-Blöcke)
- Test: `src/test/java/com/example/stemplekarte/service/QrPayloadTest.java`

**Interfaces:**
- Consumes: nichts.
- Produces: `record QrPayload(String customerId, String cardId)` mit `static QrPayload parse(String qrPayload)`.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`src/test/java/com/example/stemplekarte/service/QrPayloadTest.java`:

```java
package com.example.stemplekarte.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Das Parsen des QR-Inhalts lag zweimal wortgleich in CustomerService und
 * wird vom Punkte-Dienst ein drittes Mal gebraucht. Dieser Test haelt fest,
 * dass die gemeinsame Fassung sich genau wie die alten Kopien verhaelt -
 * besonders bei den Fehlerfaellen, denn deren Meldung sieht das Personal
 * im Scanner.
 */
class QrPayloadTest {

    @Test
    void liestKundeUndKarte() {
        QrPayload p = QrPayload.parse("{\"cid\":\"CUST-1\",\"cardId\":\"CARD-9\"}");
        assertThat(p.customerId()).isEqualTo("CUST-1");
        assertThat(p.cardId()).isEqualTo("CARD-9");
    }

    @Test
    void zusaetzlicheFelderStoerenNicht() {
        // Aeltere Karten hatten einen Zeitstempel im QR. Die liegen noch in
        // echten Wallets und muessen weiter lesbar sein.
        QrPayload p = QrPayload.parse("{\"cid\":\"CUST-1\",\"cardId\":\"CARD-9\",\"ts\":123}");
        assertThat(p.cardId()).isEqualTo("CARD-9");
    }

    @Test
    void keinJson_meldetUngueltigenQr() {
        assertThatThrownBy(() -> QrPayload.parse("kein json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ungueltiger QR-Code");
    }

    @Test
    void fehlendeKartenId_meldetFehlendeId() {
        assertThatThrownBy(() -> QrPayload.parse("{\"cid\":\"CUST-1\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Karten-ID");
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `./mvnw -o test -Dtest=QrPayloadTest`
Expected: FAIL, `cannot find symbol: class QrPayload`

- [ ] **Step 3: `QrPayload` anlegen**

`src/main/java/com/example/stemplekarte/service/QrPayload.java`:

```java
package com.example.stemplekarte.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Der Inhalt des Kunden-QR-Codes: Kunde und Karte, sonst nichts.
 *
 * Lag zweimal wortgleich in CustomerService (processScan, resetCard). Der
 * Punkte-Dienst braucht dasselbe, deshalb hier an einer Stelle. Die
 * Fehlermeldungen sind absichtlich wortgleich zu vorher - sie erscheinen
 * dem Personal im Scanner, und eine Aenderung waere eine Verhaltensaenderung
 * durch die Hintertuer.
 */
public record QrPayload(String customerId, String cardId) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static QrPayload parse(String qrPayload) {
        String customerId;
        String cardId;
        try {
            JsonNode node = MAPPER.readTree(qrPayload);
            customerId = node.path("cid").asText();
            cardId = node.path("cardId").asText();
        } catch (Exception e) {
            throw new IllegalArgumentException("Ungueltiger QR-Code: " + e.getMessage());
        }
        if (customerId.isBlank() || cardId.isBlank()) {
            throw new IllegalArgumentException("QR enthaelt keine Kunden- oder Karten-ID");
        }
        return new QrPayload(customerId, cardId);
    }
}
```

- [ ] **Step 4: Beide Parse-Blöcke in `CustomerService` ersetzen**

In `processScan` (heute Zeilen 150-161) den gesamten Block

```java
        String customerId;
        String cardId;
        try {
            JsonNode node = mapper.readTree(qrPayload);
            customerId = node.path("cid").asText();
            cardId = node.path("cardId").asText();
            if (customerId.isBlank() || cardId.isBlank()) {
                throw new IllegalArgumentException("QR enthaelt keine Kunden- oder Karten-ID");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Ungueltiger QR-Code: " + e.getMessage());
        }
```

ersetzen durch:

```java
        QrPayload qr = QrPayload.parse(qrPayload);
        String customerId = qr.customerId();
        String cardId = qr.cardId();
```

Denselben Block in `resetCard` (heute Zeilen 218-229) genauso ersetzen.

Danach das Feld `private final ObjectMapper mapper = new ObjectMapper();` und den Import `com.fasterxml.jackson.databind.JsonNode` entfernen, falls sie sonst nirgends mehr benutzt werden. Prüfen mit:

```bash
grep -n "mapper\.\|JsonNode" src/main/java/com/example/stemplekarte/service/CustomerService.java
```

- [ ] **Step 5: Tests laufen lassen**

Run: `./mvnw -o test`
Expected: PASS. `QrPayloadTest` neu grün, `ScanControllerTest` unverändert grün — letzterer ist hier der Wächter, dass der Scan-Weg nicht angefasst wurde.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/stemplekarte/service/QrPayload.java \
        src/main/java/com/example/stemplekarte/service/CustomerService.java \
        src/test/java/com/example/stemplekarte/service/QrPayloadTest.java
git commit -m "refactor: QR-Inhalt an einer Stelle parsen statt dreimal"
```

---

### Task 3: `Card` bekommt Typ, Kurs und Rundung

**Files:**
- Modify: `src/main/java/com/example/stemplekarte/model/Card.java`
- Modify: `src/main/java/com/example/stemplekarte/service/CardService.java`
- Test: `src/test/java/com/example/stemplekarte/model/PunkteKarteTest.java`

**Interfaces:**
- Consumes: `CardType`, `PointsRounding`, `PointsMath` (Task 1).
- Produces:
  - `Card.createPoints(Shop shop, String name, String description, int pointsPerEuroX100, PointsRounding rounding) -> Card`
  - `Card.getType() -> CardType` (nie null, Default `STAMP`)
  - `Card.isPoints() -> boolean`
  - `Card.getPointsPerEuroX100() -> Integer`, `Card.getPointsRounding() -> PointsRounding`
  - `Card.updatePointsSettings(Integer pointsPerEuroX100, PointsRounding rounding)`
  - `CardService.createPoints(Shop, String name, String description, int pointsPerEuroX100, PointsRounding) -> Card`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`src/test/java/com/example/stemplekarte/model/PunkteKarteTest.java`:

```java
package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nagelt fest, dass bestehende Karten Stempelkarten bleiben.
 *
 * Die Spalte type kommt per ddl-auto dazu und ist bei jeder vorhandenen
 * Zeile zunaechst NULL. Wuerde getType() das durchreichen, liefen alle
 * Karten, die gerade in echten Laeden liegen, in einen NullPointer oder
 * schlimmer: in den Punkte-Zweig.
 */
class PunkteKarteTest {

    @Test
    void neueStempelkarte_istStempelkarte() {
        Card c = Card.create(null, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        assertThat(c.getType()).isEqualTo(CardType.STAMP);
        assertThat(c.isPoints()).isFalse();
    }

    @Test
    void neuePunktekarte_traegtKursUndRundung() {
        Card c = Card.createPoints(null, "Bistro", "Punkte sammeln",
                100, PointsRounding.GENAU);
        assertThat(c.getType()).isEqualTo(CardType.POINTS);
        assertThat(c.isPoints()).isTrue();
        assertThat(c.getPointsPerEuroX100()).isEqualTo(100);
        assertThat(c.getPointsRounding()).isEqualTo(PointsRounding.GENAU);
    }

    @Test
    void punktekarte_haeltDieNotNullSpaltenBesetzt() {
        // reward_threshold und reward_text sind NOT NULL und bleiben es -
        // eine bestehende Spalte nachtraeglich nullable zu machen schafft
        // ddl-auto nicht zuverlaessig. Bei Punktekarten stehen sie auf
        // unauffaelligen Werten und werden nirgends angezeigt.
        Card c = Card.createPoints(null, "Bistro", "Punkte sammeln",
                100, PointsRounding.GENAU);
        assertThat(c.getRewardThreshold()).isEqualTo(1);
        assertThat(c.getRewardText()).isEmpty();
    }

    @Test
    void kursUndRundungSpaeterAenderbar() {
        Card c = Card.createPoints(null, "Bistro", "Punkte sammeln",
                100, PointsRounding.GENAU);
        c.updatePointsSettings(500, PointsRounding.ABRUNDEN);
        assertThat(c.getPointsPerEuroX100()).isEqualTo(500);
        assertThat(c.getPointsRounding()).isEqualTo(PointsRounding.ABRUNDEN);
    }

    @Test
    void nullWerteBeimAendernLassenAltesStehen() {
        // Gleiches Muster wie updateDesign/updateColors: was nicht
        // mitgeschickt wird, bleibt wie es war.
        Card c = Card.createPoints(null, "Bistro", "Punkte sammeln",
                100, PointsRounding.GENAU);
        c.updatePointsSettings(null, null);
        assertThat(c.getPointsPerEuroX100()).isEqualTo(100);
        assertThat(c.getPointsRounding()).isEqualTo(PointsRounding.GENAU);
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `./mvnw -o test -Dtest=PunkteKarteTest`
Expected: FAIL, `cannot find symbol: method createPoints`

- [ ] **Step 3: `Card` erweitern**

In `Card.java` nach dem Block `// ── Stempel-Design (pro Karte) ───` einfügen:

```java
    // ── Kartentyp und Punkte-Einstellungen ────────────────────────────────
    // type kommt per ddl-auto dazu und ist bei allen bestehenden Zeilen
    // zunaechst NULL. Der Getter faengt das ab, damit vorhandene Karten
    // sicher Stempelkarten bleiben.
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16, columnDefinition = "varchar(16) default 'STAMP'")
    private CardType type;

    @Column(name = "points_per_euro_x100")
    private Integer pointsPerEuroX100;

    @Enumerated(EnumType.STRING)
    @Column(name = "points_rounding", length = 16)
    private PointsRounding pointsRounding;
```

Nach der bestehenden `create`-Methode einfügen:

```java
    /**
     * Punktekarte. reward_threshold und reward_text sind NOT NULL und
     * bleiben es - eine bestehende Spalte nachtraeglich nullable zu machen
     * schafft ddl-auto nicht zuverlaessig. Deshalb stehen sie hier auf
     * unauffaelligen Werten und werden nirgends angezeigt.
     */
    public static Card createPoints(Shop shop, String name, String description,
                                    int pointsPerEuroX100, PointsRounding rounding) {
        Card c = create(shop, name, description, 1, "");
        c.type = CardType.POINTS;
        c.pointsPerEuroX100 = pointsPerEuroX100;
        c.pointsRounding = rounding;
        // Stempel-Design zeichnet bei Punkten nichts. Die Felder bleiben auf
        // ihren Defaults stehen, damit kein Zweig auf null laeuft.
        return c;
    }

    /** Was nicht mitgeschickt wird, bleibt stehen - wie updateDesign. */
    public void updatePointsSettings(Integer pointsPerEuroX100, PointsRounding rounding) {
        if (pointsPerEuroX100 != null) this.pointsPerEuroX100 = pointsPerEuroX100;
        if (rounding != null) this.pointsRounding = rounding;
    }
```

Bei den Gettern ergänzen:

```java
    public CardType getType() { return type != null ? type : CardType.STAMP; }
    public boolean isPoints() { return getType() == CardType.POINTS; }
    public Integer getPointsPerEuroX100() { return pointsPerEuroX100; }
    public PointsRounding getPointsRounding() {
        return pointsRounding != null ? pointsRounding : PointsRounding.GENAU;
    }
```

- [ ] **Step 4: `CardService.createPoints` ergänzen**

In `CardService.java` nach der bestehenden `create`-Methode:

```java
    @Transactional
    public Card createPoints(Shop shop, String name, String description,
                             int pointsPerEuroX100, PointsRounding rounding) {
        if (pointsPerEuroX100 < 1 || pointsPerEuroX100 > PointsMath.MAX_POINTS_PER_EURO_X100) {
            throw new IllegalArgumentException(
                    "Kurs muss zwischen 1 und " + PointsMath.MAX_POINTS_PER_EURO_X100 + " liegen");
        }
        return cardRepo.save(Card.createPoints(shop, name, description,
                pointsPerEuroX100, rounding));
    }
```

Importe ergänzen: `com.example.stemplekarte.model.PointsRounding`.

- [ ] **Step 5: Tests laufen lassen**

Run: `./mvnw -o test`
Expected: PASS, `PunkteKarteTest` mit 5 Tests grün

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/stemplekarte/model/Card.java \
        src/main/java/com/example/stemplekarte/service/CardService.java \
        src/test/java/com/example/stemplekarte/model/PunkteKarteTest.java
git commit -m "feat: Card bekommt Typ, Kurs und Rundungsregel"
```

---

### Task 4: Prämien-Katalog (`Reward`)

**Files:**
- Create: `src/main/java/com/example/stemplekarte/model/Reward.java`
- Create: `src/main/java/com/example/stemplekarte/repository/RewardRepository.java`
- Create: `src/main/java/com/example/stemplekarte/service/RewardService.java`
- Test: `src/test/java/com/example/stemplekarte/service/PraemienKatalogTest.java`

**Interfaces:**
- Consumes: `Card` (Task 3).
- Produces:
  - `Reward.create(Card card, String name, long costPointsX100, int sortOrder) -> Reward`, Getter `getId/getCard/getName/getCostPointsX100/getSortOrder/isActive`
  - `RewardRepository.findByCardAndActiveTrueOrderBySortOrderAscCostPointsX100Asc(Card) -> List<Reward>`
  - `RewardService.add(Card, String name, long costPointsX100) -> Reward`
  - `RewardService.list(Card) -> List<Reward>`
  - `RewardService.deactivate(String rewardId, Card card)`
  - `RewardService.naechstesZiel(List<Reward> katalog, long standX100) -> Reward` (null bei leerem Katalog)
  - Konstante `RewardService.MAX_REWARDS = 20`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`src/test/java/com/example/stemplekarte/service/PraemienKatalogTest.java`:

```java
package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Reward;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Das Ziel auf der Wallet-Karte ist die billigste Praemie, die der Kunde
 * sich noch NICHT leisten kann. Ohne diese Regel stuende dort bei jedem
 * Stand dieselbe Praemie, und der Zugreiz der Stempelkarte ("noch zwei,
 * dann ist der Kuchen drin") ginge verloren.
 *
 * Die Randfaelle sind der eigentliche Grund fuer diesen Test: leerer
 * Katalog und ein Kunde, der sich alles leisten kann.
 */
class PraemienKatalogTest {

    private Card punktekarte() {
        return Card.createPoints(null, "Bistro", "Punkte", 100, PointsRounding.GENAU);
    }

    private List<Reward> katalog(Card c) {
        return List.of(
                Reward.create(c, "Kaffee", 10_000, 0),   // 100 Punkte
                Reward.create(c, "Kuchen", 25_000, 1),   // 250 Punkte
                Reward.create(c, "Tasse", 80_000, 2)     // 800 Punkte
        );
    }

    @Test
    void zielIstBilligsteNochNichtBezahlbare() {
        Card c = punktekarte();
        // Stand 340 Punkte: Kaffee (100) ist bezahlt, Kuchen (250) auch,
        // also ist die Tasse das naechste Ziel.
        Reward ziel = RewardService.naechstesZiel(katalog(c), 34_000);
        assertThat(ziel.getName()).isEqualTo("Tasse");
    }

    @Test
    void frischeKarte_zieltAufDieBilligste() {
        Card c = punktekarte();
        Reward ziel = RewardService.naechstesZiel(katalog(c), 0);
        assertThat(ziel.getName()).isEqualTo("Kaffee");
    }

    @Test
    void allesBezahlbar_zieltAufDieTeuerste() {
        Card c = punktekarte();
        Reward ziel = RewardService.naechstesZiel(katalog(c), 99_900);
        assertThat(ziel.getName()).isEqualTo("Tasse");
    }

    @Test
    void leererKatalog_hatKeinZiel() {
        assertThat(RewardService.naechstesZiel(List.of(), 34_000)).isNull();
    }

    @Test
    void genauBezahlbar_zaehltAlsErreicht() {
        Card c = punktekarte();
        // Stand exakt 100 Punkte: Kaffee ist bezahlbar, also zielt die Karte
        // schon auf den Kuchen.
        Reward ziel = RewardService.naechstesZiel(katalog(c), 10_000);
        assertThat(ziel.getName()).isEqualTo("Kuchen");
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `./mvnw -o test -Dtest=PraemienKatalogTest`
Expected: FAIL, `cannot find symbol: class Reward`

- [ ] **Step 3: `Reward` anlegen**

`src/main/java/com/example/stemplekarte/model/Reward.java`:

```java
package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Eine Praemie im Katalog einer Punktekarte.
 *
 * Wird nie hart geloescht, nur auf active=false gesetzt: sonst verlieren
 * alte Buchungen ihren Bezug, und die Frage "was hat der Kunde damals
 * bekommen" ist nicht mehr zu beantworten.
 *
 * Der Preis liegt wie jeder Punktwert in Hundertsteln (25000 = 250 Punkte).
 */
@Entity
@Table(name = "rewards",
        indexes = @Index(name = "idx_reward_card", columnList = "card_id"))
public class Reward {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(name = "cost_points_x100", nullable = false)
    private long costPointsX100;

    @Column(name = "sort_order", nullable = false, columnDefinition = "integer default 0")
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Reward() {}

    public static Reward create(Card card, String name, long costPointsX100, int sortOrder) {
        Reward r = new Reward();
        r.id = "RW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        r.card = card;
        r.name = name;
        r.costPointsX100 = costPointsX100;
        r.sortOrder = sortOrder;
        r.active = true;
        r.createdAt = Instant.now();
        return r;
    }

    public void setActive(boolean active) { this.active = active; }
    public void setName(String name) { this.name = name; }
    public void setCostPointsX100(long costPointsX100) { this.costPointsX100 = costPointsX100; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getId() { return id; }
    public Card getCard() { return card; }
    public String getName() { return name; }
    public long getCostPointsX100() { return costPointsX100; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 4: Repository anlegen**

`src/main/java/com/example/stemplekarte/repository/RewardRepository.java`:

```java
package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Reward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewardRepository extends JpaRepository<Reward, String> {

    // Nach der vom Laden gesetzten Reihenfolge, bei Gleichstand nach Preis.
    // Der Preis als zweites Kriterium haelt die Pass-Rueckseite lesbar, auch
    // wenn ein Laden die Sortierung nie angefasst hat (alle auf 0).
    List<Reward> findByCardAndActiveTrueOrderBySortOrderAscCostPointsX100Asc(Card card);

    long countByCardAndActiveTrue(Card card);
}
```

- [ ] **Step 5: `RewardService` anlegen**

`src/main/java/com/example/stemplekarte/service/RewardService.java`:

```java
package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.repository.RewardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class RewardService {

    /** Mehr Zeilen macht die Rueckseite der Wallet-Karte unlesbar. */
    public static final int MAX_REWARDS = 20;

    private final RewardRepository rewardRepo;

    public RewardService(RewardRepository rewardRepo) {
        this.rewardRepo = rewardRepo;
    }

    @Transactional
    public Reward add(Card card, String name, long costPointsX100) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Praemie braucht einen Namen");
        }
        if (name.length() > 40) {
            throw new IllegalArgumentException("Name der Praemie: hoechstens 40 Zeichen");
        }
        if (costPointsX100 < 1) {
            throw new IllegalArgumentException("Praemie muss mindestens einen Punkt kosten");
        }
        if (rewardRepo.countByCardAndActiveTrue(card) >= MAX_REWARDS) {
            throw new IllegalArgumentException(
                    "Hoechstens " + MAX_REWARDS + " Praemien je Karte");
        }
        int naechsteReihe = list(card).size();
        return rewardRepo.save(Reward.create(card, name.trim(), costPointsX100, naechsteReihe));
    }

    public List<Reward> list(Card card) {
        return rewardRepo.findByCardAndActiveTrueOrderBySortOrderAscCostPointsX100Asc(card);
    }

    public Reward getByIdAndCard(String rewardId, Card card) {
        Reward r = rewardRepo.findById(rewardId)
                .orElseThrow(() -> new NoSuchElementException("Praemie nicht gefunden"));
        if (!r.getCard().getId().equals(card.getId())) {
            throw new IllegalArgumentException("Praemie gehoert nicht zu dieser Karte");
        }
        return r;
    }

    @Transactional
    public void deactivate(String rewardId, Card card) {
        Reward r = getByIdAndCard(rewardId, card);
        r.setActive(false);
        rewardRepo.save(r);
    }

    /**
     * Das Ziel fuer die Wallet-Karte: die billigste Praemie, die der Kunde
     * sich noch NICHT leisten kann.
     *
     * Kann er sich alles leisten, ist die teuerste das Ziel - sonst stuende
     * auf einer vollen Karte gar nichts. Bei leerem Katalog gibt es kein
     * Ziel; die Karte zeigt dann nur den Punktestand.
     *
     * Bewusst statisch und ohne Datenbank, damit die Regel in einem
     * Einheitentest festzunageln ist.
     */
    public static Reward naechstesZiel(List<Reward> katalog, long standX100) {
        if (katalog.isEmpty()) return null;
        return katalog.stream()
                .filter(r -> r.getCostPointsX100() > standX100)
                .min(Comparator.comparingLong(Reward::getCostPointsX100))
                .orElseGet(() -> katalog.stream()
                        .max(Comparator.comparingLong(Reward::getCostPointsX100))
                        .orElse(null));
    }
}
```

- [ ] **Step 6: Tests laufen lassen**

Run: `./mvnw -o test`
Expected: PASS, `PraemienKatalogTest` mit 5 Tests grün

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/stemplekarte/model/Reward.java \
        src/main/java/com/example/stemplekarte/repository/RewardRepository.java \
        src/main/java/com/example/stemplekarte/service/RewardService.java \
        src/test/java/com/example/stemplekarte/service/PraemienKatalogTest.java
git commit -m "feat: Praemien-Katalog fuer Punktekarten"
```

---

### Task 5: Punktestand und Buchungszeile

**Files:**
- Modify: `src/main/java/com/example/stemplekarte/model/CustomerCard.java`
- Create: `src/main/java/com/example/stemplekarte/model/BookingKind.java`
- Create: `src/main/java/com/example/stemplekarte/model/PointsBooking.java`
- Create: `src/main/java/com/example/stemplekarte/repository/PointsBookingRepository.java`
- Test: `src/test/java/com/example/stemplekarte/model/PunkteStandTest.java`

**Interfaces:**
- Consumes: nichts aus früheren Tasks.
- Produces:
  - `CustomerCard.getPointsX100() -> long`
  - `CustomerCard.addPoints(long deltaX100) -> long` (gibt zurück, wie viel tatsächlich gebucht wurde)
  - `CustomerCard.kannBezahlen(long kostenX100) -> boolean`
  - `enum BookingKind { EARN, REDEEM, CORRECTION, REVERSAL }`
  - `PointsBooking.earn(...)`, `.redeem(...)`, `.correction(...)`, `.reversal(...)` — Signaturen in Step 4
  - `PointsBookingRepository.findTop10ByCustomerCardIdOrderByCreatedAtDesc(String) -> List<PointsBooking>`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`src/test/java/com/example/stemplekarte/model/PunkteStandTest.java`:

```java
package com.example.stemplekarte.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Punktestand liegt in Hundertsteln, damit 5,20 Euro bei 1 Euro =
 * 1 Punkt sauber 5,2 Punkte ergeben, ohne dass ein double ins Spiel kommt.
 *
 * Der wichtige Fall ist der Abzug unter den Bestand: eine Korrektur darf
 * die Karte nicht ins Minus druecken. Gleiches Muster wie redeemReward bei
 * den Stempeln, das seit jeher mit Math.max(0, ...) arbeitet. addPoints
 * gibt deshalb zurueck, was TATSAECHLICH gebucht wurde - die Buchungszeile
 * soll die Wirklichkeit festhalten, nicht die Absicht.
 */
class PunkteStandTest {

    @Test
    void frischeKarte_hatNullPunkte() {
        CustomerCard cc = CustomerCard.create(null, null);
        assertThat(cc.getPointsX100()).isZero();
    }

    @Test
    void punkteKommenDazu() {
        CustomerCard cc = CustomerCard.create(null, null);
        long gebucht = cc.addPoints(520);
        assertThat(gebucht).isEqualTo(520);
        assertThat(cc.getPointsX100()).isEqualTo(520);
    }

    @Test
    void abzugGehtNichtUnterNull() {
        CustomerCard cc = CustomerCard.create(null, null);
        cc.addPoints(300);
        long gebucht = cc.addPoints(-500);
        // Nur 300 waren da, also wurden auch nur 300 abgezogen.
        assertThat(gebucht).isEqualTo(-300);
        assertThat(cc.getPointsX100()).isZero();
    }

    @Test
    void stempelBleibenUnberuehrt() {
        // Punktebuchungen duerfen den Stempelstand nicht anfassen - sonst
        // waere eine Karte, die beide Spalten traegt, nicht mehr eindeutig.
        CustomerCard cc = CustomerCard.create(null, null);
        cc.addStamp();
        cc.addPoints(1000);
        assertThat(cc.getStamps()).isEqualTo(1);
    }

    @Test
    void bezahlbarkeitPruefen() {
        CustomerCard cc = CustomerCard.create(null, null);
        cc.addPoints(25_000);
        assertThat(cc.kannBezahlen(25_000)).isTrue();   // genau reicht
        assertThat(cc.kannBezahlen(25_001)).isFalse();
    }

    @Test
    void resetLoeschtAuchPunkte() {
        // Der Reset-Knopf im Scanner setzt die Karte komplett zurueck. Bei
        // einer Punktekarte muss das die Punkte mitnehmen, sonst bleibt ein
        // Guthaben auf einer angeblich frischen Karte stehen.
        CustomerCard cc = CustomerCard.create(null, null);
        cc.addPoints(25_000);
        cc.resetAll();
        assertThat(cc.getPointsX100()).isZero();
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `./mvnw -o test -Dtest=PunkteStandTest`
Expected: FAIL, `cannot find symbol: method addPoints`

- [ ] **Step 3: `CustomerCard` erweitern**

Feld ergänzen, direkt nach `private int totalRewards;`:

```java
    // Punktestand in Hundertstel-Punkten (520 = 5,20 Punkte). Gilt nur fuer
    // Karten vom Typ POINTS; Stempelkarten lassen die Spalte auf 0 stehen.
    // Default in der Spaltendefinition, damit ddl-auto=update bei
    // bestehenden Zeilen nicht fehlschlaegt.
    @Column(name = "points_x100", nullable = false,
            columnDefinition = "bigint not null default 0")
    private long pointsX100;
```

Methoden ergänzen, nach `redeemReward`:

```java
    /**
     * Bucht Punkte auf oder ab und gibt zurueck, wie viel TATSAECHLICH
     * gebucht wurde.
     *
     * Der Bestand geht nie unter null - gleiches Muster wie redeemReward bei
     * den Stempeln. Bei einer Korrektur ueber mehr als den Bestand faellt
     * der Rueckgabewert deshalb kleiner aus als der Wunsch, und genau dieser
     * Rueckgabewert landet in der Buchungszeile: sie soll festhalten, was
     * passiert ist, nicht was gemeint war.
     */
    public long addPoints(long deltaX100) {
        long vorher = this.pointsX100;
        this.pointsX100 = Math.max(0, vorher + deltaX100);
        this.updatedAt = Instant.now();
        return this.pointsX100 - vorher;
    }

    public boolean kannBezahlen(long kostenX100) {
        return this.pointsX100 >= kostenX100;
    }
```

In `resetAll()` die Zeile ergänzen:

```java
        this.pointsX100 = 0;
```

Getter ergänzen:

```java
    public long getPointsX100() { return pointsX100; }
```

- [ ] **Step 4: `BookingKind` und `PointsBooking` anlegen**

`src/main/java/com/example/stemplekarte/model/BookingKind.java`:

```java
package com.example.stemplekarte.model;

public enum BookingKind {
    /** Einkauf gebucht. */
    EARN,
    /** Praemie abgebucht. */
    REDEEM,
    /** Handkorrektur mit Vorzeichen. */
    CORRECTION,
    /** Gegenbuchung zu einer frueheren Buchung. */
    REVERSAL
}
```

`src/main/java/com/example/stemplekarte/model/PointsBooking.java`:

```java
package com.example.stemplekarte.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Eine Punktebewegung. Aus dieser Tabelle fallen Handkorrektur, Ruecknahme,
 * Statistik und die Antwort auf "was ist an dem Dienstag passiert".
 *
 * Bewusst NICHT der ScanLog: der ist wegwerfbar gebaut, sein Schreibfehler
 * wird geschluckt, damit ein Scan nie an der Statistik scheitert. Eine
 * Buchung darf das nicht - scheitert sie, muss der Punktestand mit
 * zurueckrollen.
 *
 * Nur IDs, keine harten Beziehungen - wie ScanLog, damit das Loeschen einer
 * Karte nicht an Buchungen scheitert.
 *
 * Gespeichert wird staffLabel ("Kasse 1"), NICHT das Staff-Token: dessen
 * Wert ist zugleich Primaerschluessel und Zugangsberechtigung im
 * X-Staff-Token-Header. Da der Scanner Buchungen anzeigt, waere die
 * Berechtigung eines Geraets sonst ueber die Buchungsliste ablesbar.
 */
@Entity
@Table(name = "points_bookings",
        indexes = {
                @Index(name = "idx_booking_cc_time", columnList = "customer_card_id, created_at"),
                @Index(name = "idx_booking_shop_time", columnList = "shop_id, created_at")
        })
public class PointsBooking {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "customer_card_id", nullable = false, length = 64)
    private String customerCardId;

    @Column(name = "card_id", nullable = false, length = 64)
    private String cardId;

    @Column(name = "shop_id", nullable = false, length = 64)
    private String shopId;

    @Column(name = "customer_id", length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingKind kind;

    /** Mit Vorzeichen. Was tatsaechlich gebucht wurde, nicht was gemeint war. */
    @Column(name = "delta_points_x100", nullable = false)
    private long deltaPointsX100;

    @Column(name = "amount_cents")
    private Long amountCents;

    /** Abschrift des Kurses, der galt. */
    @Column(name = "points_per_euro_x100")
    private Integer pointsPerEuroX100;

    @Column(name = "reward_id", length = 64)
    private String rewardId;

    /** Abschrift: benennt der Laden die Praemie um, bleibt die Historie wahr. */
    @Column(name = "reward_name", length = 40)
    private String rewardName;

    @Column(name = "reward_cost_points_x100")
    private Long rewardCostPointsX100;

    @Column(name = "staff_label")
    private String staffLabel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reversal_of_id", length = 64)
    private String reversalOfId;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    protected PointsBooking() {}

    private static PointsBooking basis(CustomerCard cc, String shopId,
                                       BookingKind kind, long deltaPointsX100,
                                       String staffLabel) {
        PointsBooking b = new PointsBooking();
        b.id = "PB-" + UUID.randomUUID();
        b.customerCardId = cc.getId();
        b.cardId = cc.getCard().getId();
        b.shopId = shopId;
        b.customerId = cc.getCustomer().getId();
        b.kind = kind;
        b.deltaPointsX100 = deltaPointsX100;
        b.staffLabel = staffLabel;
        b.createdAt = Instant.now();
        return b;
    }

    public static PointsBooking earn(CustomerCard cc, String shopId, long deltaPointsX100,
                                     long amountCents, int pointsPerEuroX100,
                                     String staffLabel) {
        PointsBooking b = basis(cc, shopId, BookingKind.EARN, deltaPointsX100, staffLabel);
        b.amountCents = amountCents;
        b.pointsPerEuroX100 = pointsPerEuroX100;
        return b;
    }

    public static PointsBooking redeem(CustomerCard cc, String shopId, Reward reward,
                                       String staffLabel) {
        PointsBooking b = basis(cc, shopId, BookingKind.REDEEM,
                -reward.getCostPointsX100(), staffLabel);
        b.rewardId = reward.getId();
        b.rewardName = reward.getName();
        b.rewardCostPointsX100 = reward.getCostPointsX100();
        return b;
    }

    public static PointsBooking correction(CustomerCard cc, String shopId,
                                           long deltaPointsX100, Long amountCents,
                                           Integer pointsPerEuroX100, String staffLabel) {
        PointsBooking b = basis(cc, shopId, BookingKind.CORRECTION, deltaPointsX100, staffLabel);
        b.amountCents = amountCents;
        b.pointsPerEuroX100 = pointsPerEuroX100;
        return b;
    }

    public static PointsBooking reversal(CustomerCard cc, String shopId,
                                         PointsBooking original, long deltaPointsX100,
                                         String staffLabel) {
        PointsBooking b = basis(cc, shopId, BookingKind.REVERSAL, deltaPointsX100, staffLabel);
        b.reversalOfId = original.getId();
        // Die Praemien-Abschrift wandert mit, damit in der Liste steht,
        // WAS zurueckgenommen wurde.
        b.rewardId = original.getRewardId();
        b.rewardName = original.getRewardName();
        b.rewardCostPointsX100 = original.getRewardCostPointsX100();
        b.amountCents = original.getAmountCents();
        return b;
    }

    public void markiereAlsZurueckgenommen() {
        this.reversedAt = Instant.now();
    }

    public boolean istZurueckgenommen() { return reversedAt != null; }

    public String getId() { return id; }
    public String getCustomerCardId() { return customerCardId; }
    public String getCardId() { return cardId; }
    public String getShopId() { return shopId; }
    public String getCustomerId() { return customerId; }
    public BookingKind getKind() { return kind; }
    public long getDeltaPointsX100() { return deltaPointsX100; }
    public Long getAmountCents() { return amountCents; }
    public Integer getPointsPerEuroX100() { return pointsPerEuroX100; }
    public String getRewardId() { return rewardId; }
    public String getRewardName() { return rewardName; }
    public Long getRewardCostPointsX100() { return rewardCostPointsX100; }
    public String getStaffLabel() { return staffLabel; }
    public Instant getCreatedAt() { return createdAt; }
    public String getReversalOfId() { return reversalOfId; }
    public Instant getReversedAt() { return reversedAt; }
}
```

- [ ] **Step 5: Repository anlegen**

`src/main/java/com/example/stemplekarte/repository/PointsBookingRepository.java`:

```java
package com.example.stemplekarte.repository;

import com.example.stemplekarte.model.PointsBooking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PointsBookingRepository extends JpaRepository<PointsBooking, String> {

    // Die letzten Buchungen einer Karte, neueste zuerst. Zehn reichen fuer
    // den Scanner: dort wird nur die juengste zum Zuruecknehmen angeboten,
    // der Rest dient der Nachschau an der Theke.
    List<PointsBooking> findTop10ByCustomerCardIdOrderByCreatedAtDesc(String customerCardId);

    // Fuer die Statistik: alle Buchungen eines Ladens ab einem Zeitpunkt.
    List<PointsBooking> findByShopIdAndCreatedAtAfterOrderByCreatedAtAsc(
            String shopId, Instant after);
}
```

- [ ] **Step 6: Tests laufen lassen**

Run: `./mvnw -o test`
Expected: PASS. `PunkteStandTest` mit 6 Tests grün, `CustomerCardTest` unverändert grün.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/stemplekarte/model/CustomerCard.java \
        src/main/java/com/example/stemplekarte/model/BookingKind.java \
        src/main/java/com/example/stemplekarte/model/PointsBooking.java \
        src/main/java/com/example/stemplekarte/repository/PointsBookingRepository.java \
        src/test/java/com/example/stemplekarte/model/PunkteStandTest.java
git commit -m "feat: Punktestand auf der Kundenkarte und Buchungszeile"
```

---

### Task 6: `WalletNotifier` — den Benachrichtigungsblock herausziehen

`ScanController` hat „SSE senden, APNs pushen, Google updaten" zweimal inline (Scan und Reset). Die vier neuen Punkte-Endpunkte würden daraus sechs Kopien machen. Vorher an eine Stelle.

Rein mechanisch. `ScanControllerTest` ist der Wächter.

**Files:**
- Create: `src/main/java/com/example/stemplekarte/wallet/WalletNotifier.java`
- Modify: `src/main/java/com/example/stemplekarte/controller/ScanController.java`
- Test: `src/test/java/com/example/stemplekarte/wallet/WalletNotifierTest.java`

**Interfaces:**
- Consumes: `CardEventHub`, `ApnsPushService`, `GoogleWalletService` (bestehend).
- Produces: `WalletNotifier.nachStempelAenderung(CustomerCard cc)` und `WalletNotifier.nachPunkteAenderung(String customerCardId, long standX100, String zielName, long fehlendX100)`.

> **Warum der Punkte-Weg nur die ID nimmt:** alle drei Wege brauchen von der
> Karte ausschliesslich `getId()`. Eine `CustomerCard` zu verlangen, würde den
> Controller zwingen, die Entity über die Transaktionsgrenze zu halten — und
> mit `open-in-view: false` endet das in einer `LazyInitializationException`,
> nachdem die Buchung bereits geschrieben ist.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`src/test/java/com/example/stemplekarte/wallet/WalletNotifierTest.java`:

```java
package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.service.CardEventHub;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Der Benachrichtigungsblock lag zweimal inline im ScanController und haette
 * mit den Punkte-Endpunkten sechs Kopien ergeben.
 *
 * Die eine Regel, die dabei nicht verlorengehen darf: ein fehlgeschlagener
 * Push bricht den Vorgang NIE ab. Der Stempel beziehungsweise die Buchung
 * ist zu dem Zeitpunkt schon gesetzt - wuerde hier eine Ausnahme
 * durchschlagen, meldete der Scanner einen Fehler fuer etwas, das
 * tatsaechlich stattgefunden hat, und das Personal bucht ein zweites Mal.
 */
class WalletNotifierTest {

    private CustomerCard karte() {
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn("CUST-1");
        Card card = mock(Card.class);
        when(card.getId()).thenReturn("CARD-1");
        when(card.getRewardThreshold()).thenReturn(10);
        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getId()).thenReturn("CC-1");
        when(cc.getCustomer()).thenReturn(customer);
        when(cc.getCard()).thenReturn(card);
        when(cc.getStamps()).thenReturn(3);
        when(cc.getTotalRewards()).thenReturn(0);
        return cc;
    }

    @Test
    void schicktAlleDreiWege() {
        CardEventHub hub = mock(CardEventHub.class);
        ApnsPushService apns = mock(ApnsPushService.class);
        GoogleWalletService google = mock(GoogleWalletService.class);

        new WalletNotifier(hub, apns, google).nachStempelAenderung(karte());

        verify(hub).publishStamps(anyString(), anyInt(), anyInt(), anyInt());
        verify(apns).notifyUpdate("CC-1");
        verify(google).notifyUpdate("CC-1");
    }

    @Test
    void einFehlgeschlagenerPushBrichtNichtAb() {
        CardEventHub hub = mock(CardEventHub.class);
        ApnsPushService apns = mock(ApnsPushService.class);
        GoogleWalletService google = mock(GoogleWalletService.class);

        doThrow(new RuntimeException("APNs weg")).when(apns).notifyUpdate(anyString());

        WalletNotifier notifier = new WalletNotifier(hub, apns, google);

        assertDoesNotThrow(() -> notifier.nachStempelAenderung(karte()));
        // Google wird trotzdem noch versucht - ein toter Weg darf den
        // naechsten nicht mitreissen.
        verify(google).notifyUpdate("CC-1");
    }

    @Test
    void punkteWegSchicktEigenesEreignis() {
        CardEventHub hub = mock(CardEventHub.class);
        ApnsPushService apns = mock(ApnsPushService.class);
        GoogleWalletService google = mock(GoogleWalletService.class);

        new WalletNotifier(hub, apns, google)
                .nachPunkteAenderung("CC-1", 34_000, "Kuchen", 16_000);

        verify(hub).publishPoints("CC-1", 34_000, "Kuchen", 16_000);
        verify(apns).notifyUpdate("CC-1");
        verify(google).notifyUpdate("CC-1");
    }
}
```

> **Hinweis:** `CardEventHub.publishPoints` gibt es noch nicht. Der Test verlangt sie. Sie wird in Step 3 angelegt — SSE-Push an die Kundenseite. Die Kundenseite selbst (das HTML, das darauf hört) kommt erst in Plan 3; der Push läuft bis dahin ins Leere, was der Hub ohnehin abfängt (`if (list == null || list.isEmpty()) return;`).

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `./mvnw -o test -Dtest=WalletNotifierTest`
Expected: FAIL, `cannot find symbol: class WalletNotifier`

- [ ] **Step 3: `CardEventHub.publishPoints` ergänzen**

In `CardEventHub.java` nach `publishStamps` einfügen:

```java
    /**
     * Neuen Punktestand an alle offenen Kartenseiten dieser Karte pushen.
     *
     * Eigenes Ereignis statt publishStamps mitzubenutzen: die Kundenseite
     * zeichnet fuer Punkte etwas anderes als ein Stempelraster, und ein
     * gemeinsames Ereignis mit halb gefuellten Feldern waere auf beiden
     * Seiten eine Fallunterscheidung.
     *
     * zielName darf null sein - dann hat die Karte keinen Katalog.
     */
    public void publishPoints(String customerCardId, long pointsX100,
                              String zielName, long fehlendX100) {
        List<SseEmitter> list = emitters.get(customerCardId);
        if (list == null || list.isEmpty()) return;

        String json = String.format(
                "{\"pointsX100\":%d,\"zielName\":%s,\"fehlendX100\":%d}",
                pointsX100,
                zielName == null ? "null" : "\"" + zielName.replace("\"", "\\\"") + "\"",
                fehlendX100);

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("points").data(json));
            } catch (Exception e) {
                remove(customerCardId, emitter);
            }
        }
        log.debug("SSE-Push points={} an {} Verbindung(en) fuer {}",
                pointsX100, list.size(), customerCardId);
    }
```

- [ ] **Step 4: `WalletNotifier` anlegen**

`src/main/java/com/example/stemplekarte/wallet/WalletNotifier.java`:

```java
package com.example.stemplekarte.wallet;

import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.service.CardEventHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Die drei Wege, auf denen eine Aenderung beim Kunden ankommt: SSE an die
 * offene Kartenseite, stiller APNs-Push ans iPhone, direktes Update des
 * Google-Loyalty-Objekts.
 *
 * Lag zweimal inline im ScanController. Mit den vier Punkte-Endpunkten
 * waeren daraus sechs Kopien geworden.
 *
 * Die Regel, die hier zusammengehalten wird: ein fehlgeschlagener Push
 * bricht den Vorgang NIE ab. Der Stempel beziehungsweise die Buchung ist zu
 * dem Zeitpunkt bereits gesetzt - eine durchschlagende Ausnahme meldete dem
 * Personal einen Fehler fuer etwas, das stattgefunden hat, und provoziert
 * die doppelte Buchung.
 */
@Component
public class WalletNotifier {

    private static final Logger log = LoggerFactory.getLogger(WalletNotifier.class);

    private final CardEventHub cardEventHub;
    private final ApnsPushService apnsPushService;
    private final GoogleWalletService googleWalletService;

    public WalletNotifier(CardEventHub cardEventHub,
                          ApnsPushService apnsPushService,
                          GoogleWalletService googleWalletService) {
        this.cardEventHub = cardEventHub;
        this.apnsPushService = apnsPushService;
        this.googleWalletService = googleWalletService;
    }

    /** Nach jeder Stempelaenderung (Scan, Einloesen, Reset). */
    public void nachStempelAenderung(CustomerCard cc) {
        sicher("SSE-Push", () -> cardEventHub.publishStamps(
                cc.getId(), cc.getStamps(), cc.getTotalRewards(),
                cc.getCard().getRewardThreshold()));
        walletWege(cc);
    }

    /**
     * Nach jeder Punktebuchung. zielName darf null sein (leerer Katalog).
     *
     * Nimmt bewusst nur die ID, nicht die Entity: alle drei Wege brauchen von
     * der Karte nichts weiter, und der Aufrufer sitzt ausserhalb der
     * Transaktion. Eine CustomerCard hier zu verlangen, hiesse einen
     * Lazy-Proxy ueber die geschlossene Session zu tragen.
     */
    public void nachPunkteAenderung(String customerCardId, long pointsX100,
                                    String zielName, long fehlendX100) {
        sicher("SSE-Push", () -> cardEventHub.publishPoints(
                customerCardId, pointsX100, zielName, fehlendX100));
        walletWege(customerCardId);
    }

    private void walletWege(CustomerCard cc) {
        walletWege(cc.getId());
    }

    private void walletWege(String customerCardId) {
        sicher("APNs Push", () -> apnsPushService.notifyUpdate(customerCardId));
        sicher("Google Wallet Update", () -> googleWalletService.notifyUpdate(customerCardId));
    }

    /** Zusaetzliche "Karte voll"-Meldung. Apple bekommt sie ueber das
     *  changeMessage im Pass, Google braucht den expliziten Aufruf. */
    public void googleKarteVoll(String customerCardId, String text) {
        sicher("Google Wallet 'Karte voll'",
                () -> googleWalletService.notifyCardFull(customerCardId, text));
    }

    private void sicher(String was, Runnable arbeit) {
        try {
            arbeit.run();
        } catch (Exception e) {
            log.warn("{} fehlgeschlagen (nicht kritisch): {}", was, e.getMessage());
        }
    }
}
```

- [ ] **Step 5: `ScanController` auf den Notifier umstellen**

Im Konstruktor `ApnsPushService`, `GoogleWalletService` und `CardEventHub` durch `WalletNotifier notifier` ersetzen. In `scan()` die drei `try/catch`-Blöcke (SSE, APNs, Google) durch eine Zeile ersetzen:

```java
        notifier.nachStempelAenderung(cc);
```

Den `if (rewardEarned)`-Block ersetzen durch:

```java
        if (rewardEarned) {
            notifier.googleKarteVoll(cc.getId(), cc.getCard().getRewardText());
        }
```

In `reset()` die drei Blöcke genauso durch `notifier.nachStempelAenderung(cc);` ersetzen.

- [ ] **Step 6: `ScanControllerTest` an den neuen Konstruktor anpassen**

In `ScanControllerTest.controllerMitShop` die Konstruktorzeile ersetzen:

```java
        return new ScanController(service, new WalletNotifier(
                mock(CardEventHub.class), mock(ApnsPushService.class),
                mock(GoogleWalletService.class)));
```

Import ergänzen: `com.example.stemplekarte.wallet.WalletNotifier`.

- [ ] **Step 7: Tests laufen lassen**

Run: `./mvnw -o test`
Expected: PASS. `WalletNotifierTest` mit 3 Tests grün, `ScanControllerTest` grün — das ist der Beweis, dass der Scan-Weg sich nicht verändert hat.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/stemplekarte/wallet/WalletNotifier.java \
        src/main/java/com/example/stemplekarte/service/CardEventHub.java \
        src/main/java/com/example/stemplekarte/controller/ScanController.java \
        src/test/java/com/example/stemplekarte/wallet/WalletNotifierTest.java \
        src/test/java/com/example/stemplekarte/controller/ScanControllerTest.java
git commit -m "refactor: Wallet-Benachrichtigungen an einer Stelle"
```

---

### Task 7: `PointsService` — buchen, einlösen, korrigieren, zurücknehmen

Das Herzstück. Alle vier Wege ändern den Stand und schreiben genau eine Buchung, in derselben Transaktion.

**Files:**
- Create: `src/main/java/com/example/stemplekarte/service/PointsService.java`
- Test: `src/test/java/com/example/stemplekarte/service/PunkteBuchungTest.java`

**Interfaces:**
- Consumes: `PointsMath`, `QrPayload`, `Card`, `Reward`, `RewardService`, `CustomerCard`, `PointsBooking`, `PointsBookingRepository`, `CustomerCardRepository`, `CardRepository`, `ScanLogRepository`.
- Produces:
  - `record RewardView(String id, String name, long costPointsX100, String costText, boolean bezahlbar, long fehlendX100)`
  - `record BookingView(String id, String kind, long deltaPointsX100, String deltaText, Long amountCents, String rewardName, String staffLabel, Instant createdAt)`
  - `record PointsState(String customerCardId, String customerName, String cardId, String cardName, CardType type, long pointsX100, int stamps, int rewardThreshold, String rewardText, Integer pointsPerEuroX100, String pointsRounding, List<RewardView> katalog, RewardView ziel, long fehlendX100, BookingView letzteBuchung)`
  - `record PointsResult(String customerCardId, String customerName, String cardId, String cardName, long pointsX100, String pointsText, BookingView booking, RewardView ziel, long fehlendX100, boolean neuesZielErreicht, List<RewardView> katalog)`

> **Wichtig:** Beide Ergebnis-Typen tragen **keine JPA-Entitäten**. Sie werden
> innerhalb der Transaktion gefüllt und danach vom Controller nur noch
> weitergereicht. Würde hier ein `Reward` oder eine `CustomerCard`
> durchgereicht, träfe der Controller beim Abbilden auf einen Lazy-Proxy und
> müsste nachladen — mit `open-in-view: false` ist die Session dort längst zu.
> Genau das ist am 12.09. passiert: der Stempel war gesetzt, danach flog eine
> `LazyInitializationException`, und der Scanner meldete pauschal
> „Ungueltiger QR-Code". Das Personal hat daraufhin ein zweites Mal gestempelt.
  - `PointsService.state(String qrPayload, Shop shop) -> PointsState`
  - `PointsService.earn(String qrPayload, Shop shop, long amountCents, String staffLabel) -> PointsResult`
  - `PointsService.redeem(String qrPayload, Shop shop, String rewardId, String staffLabel) -> PointsResult`
  - `PointsService.correct(String qrPayload, Shop shop, Long amountCents, Long pointsX100, String staffLabel) -> PointsResult`
  - `PointsService.undo(String qrPayload, Shop shop, String bookingId, String staffLabel) -> PointsResult`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`src/test/java/com/example/stemplekarte/service/PunkteBuchungTest.java`:

```java
package com.example.stemplekarte.service;

import com.example.stemplekarte.model.BookingKind;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.PointsBooking;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.PointsBookingRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nagelt die vier Buchungswege fest.
 *
 * Der Grund fuer die Buchungstabelle ist der Vertipper an der Kasse: 1450
 * statt 145 ist schneller getippt, als man denkt, und bei Stempeln war ein
 * Fehlgriff ein Schulterzucken - bei Punkten sind es zehnfache Punkte.
 * Deshalb muessen Korrektur und Ruecknahme genauso sicher sitzen wie das
 * Buchen selbst.
 *
 * Doppelte Ruecknahme ist der Fall, der im Laden wirklich passiert: zwei
 * Kassen, beide sehen dieselbe Buchung, beide druecken zurueck.
 */
class PunkteBuchungTest {

    private CustomerCardRepository customerCardRepo;
    private CardRepository cardRepo;
    private PointsBookingRepository bookingRepo;
    private ScanLogRepository scanLogRepo;
    private RewardService rewardService;
    private PointsService service;

    private Shop shop;
    private Card card;
    private CustomerCard cc;
    private Reward kaffee;
    private Reward kuchen;

    private static final String QR = "{\"cid\":\"CUST-1\",\"cardId\":\"CARD-1\"}";

    @BeforeEach
    void aufbau() {
        customerCardRepo = mock(CustomerCardRepository.class);
        cardRepo = mock(CardRepository.class);
        bookingRepo = mock(PointsBookingRepository.class);
        scanLogRepo = mock(ScanLogRepository.class);
        rewardService = mock(RewardService.class);

        shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");

        card = Card.createPoints(shop, "Bistro", "Punkte", 100, PointsRounding.GENAU);
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn("CUST-1");
        when(customer.getName()).thenReturn("Adham");

        cc = CustomerCard.create(customer, card);

        kaffee = Reward.create(card, "Kaffee", 10_000, 0);
        kuchen = Reward.create(card, "Kuchen", 25_000, 1);

        when(cardRepo.findById("CARD-1")).thenReturn(Optional.of(card));
        when(customerCardRepo.findByCustomerIdAndCardId("CUST-1", "CARD-1"))
                .thenReturn(Optional.of(cc));
        when(customerCardRepo.save(any(CustomerCard.class))).thenAnswer(i -> i.getArgument(0));
        when(bookingRepo.save(any(PointsBooking.class))).thenAnswer(i -> i.getArgument(0));
        when(rewardService.list(card)).thenReturn(List.of(kaffee, kuchen));

        service = new PointsService(customerCardRepo, cardRepo, bookingRepo,
                scanLogRepo, rewardService);
    }

    /**
     * Die zuletzt GESPEICHERTE Buchung. Geprueft wird die Entity, nicht die
     * zurueckgegebene Ansicht: was in der Datenbank steht, ist die Wahrheit,
     * an der spaeter eine Ruecknahme oder ein Streit an der Theke haengt.
     */
    private PointsBooking letzteGespeicherte() {
        ArgumentCaptor<PointsBooking> captor = ArgumentCaptor.forClass(PointsBooking.class);
        org.mockito.Mockito.verify(bookingRepo, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        return captor.getValue();
    }

    @Test
    void einkaufBuchtPunkteUndSchreibtBuchung() {
        var ergebnis = service.earn(QR, shop, 14_500, "Kasse 1");

        // 145,00 Euro bei 1 Euro = 1 Punkt
        assertThat(cc.getPointsX100()).isEqualTo(14_500);
        assertThat(ergebnis.pointsText()).isEqualTo("145");

        PointsBooking gespeichert = letzteGespeicherte();
        assertThat(gespeichert.getKind()).isEqualTo(BookingKind.EARN);
        assertThat(gespeichert.getDeltaPointsX100()).isEqualTo(14_500);
        assertThat(gespeichert.getAmountCents()).isEqualTo(14_500);
        // Abschrift des Kurses, damit eine spaetere Kursaenderung die
        // Historie nicht umschreibt
        assertThat(gespeichert.getPointsPerEuroX100()).isEqualTo(100);
        assertThat(gespeichert.getStaffLabel()).isEqualTo("Kasse 1");
    }

    @Test
    void ergebnisTraegtKeineEntitaeten() {
        // Der Controller sitzt ausserhalb der Transaktion. Kaeme hier ein
        // Reward oder eine CustomerCard heraus, traefe er beim Abbilden auf
        // einen Lazy-Proxy und die Session waere zu - genau der Absturz vom
        // 12.09., nachdem der Stempel bereits gesetzt war.
        var ergebnis = service.earn(QR, shop, 14_500, "Kasse 1");

        assertThat(ergebnis.katalog()).allSatisfy(r ->
                assertThat(r).isInstanceOf(PointsService.RewardView.class));
        assertThat(ergebnis.booking()).isInstanceOf(PointsService.BookingView.class);
        assertThat(ergebnis.customerCardId()).isEqualTo(cc.getId());
    }

    @Test
    void einkaufMeldetNeuErreichtesZiel() {
        // Von 0 auf 145 Punkte: der Kaffee (100) wird erreichbar.
        var ergebnis = service.earn(QR, shop, 14_500, "Kasse 1");
        assertThat(ergebnis.neuesZielErreicht()).isTrue();
        // Naechstes Ziel ist jetzt der Kuchen, es fehlen 105 Punkte.
        assertThat(ergebnis.ziel().name()).isEqualTo("Kuchen");
        assertThat(ergebnis.fehlendX100()).isEqualTo(10_500);
        assertThat(ergebnis.fehlendText()).isEqualTo("105");
    }

    @Test
    void einkaufOhneNeuesZiel_meldetKeines() {
        service.earn(QR, shop, 2_000, "Kasse 1");   // 20 Punkte, nichts erreicht
        var zweiter = service.earn(QR, shop, 1_000, "Kasse 1"); // 30 Punkte
        assertThat(zweiter.neuesZielErreicht()).isFalse();
    }

    @Test
    void einloesenZiehtDenPreisAbUndZaehltDieBelohnung() {
        service.earn(QR, shop, 30_000, "Kasse 1");  // 300 Punkte
        when(rewardService.getByIdAndCard("RW-KUCHEN", card)).thenReturn(kuchen);

        service.redeem(QR, shop, "RW-KUCHEN", "Kasse 1");

        assertThat(cc.getPointsX100()).isEqualTo(5_000);   // 300 - 250 = 50
        assertThat(cc.getTotalRewards()).isEqualTo(1);

        PointsBooking gespeichert = letzteGespeicherte();
        assertThat(gespeichert.getKind()).isEqualTo(BookingKind.REDEEM);
        // Abschrift von Name und Preis: benennt der Laden "Kuchen" spaeter
        // in "Gebaeck" um, erzaehlt die Historie trotzdem die Wahrheit.
        assertThat(gespeichert.getRewardName()).isEqualTo("Kuchen");
        assertThat(gespeichert.getRewardCostPointsX100()).isEqualTo(25_000);
    }

    @Test
    void einloesenOhneDeckungScheitert() {
        service.earn(QR, shop, 5_000, "Kasse 1");   // 50 Punkte
        when(rewardService.getByIdAndCard("RW-KUCHEN", card)).thenReturn(kuchen);

        assertThatThrownBy(() -> service.redeem(QR, shop, "RW-KUCHEN", "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nicht genug Punkte");

        assertThat(cc.getPointsX100()).isEqualTo(5_000);  // unveraendert
        assertThat(cc.getTotalRewards()).isZero();
    }

    @Test
    void korrekturMitNegativemBetrag() {
        service.earn(QR, shop, 14_500, "Kasse 1");
        service.correct(QR, shop, -13_500L, null, "Kasse 1");

        assertThat(cc.getPointsX100()).isEqualTo(1_000);  // 145 - 135 = 10
        PointsBooking gespeichert = letzteGespeicherte();
        assertThat(gespeichert.getKind()).isEqualTo(BookingKind.CORRECTION);
        assertThat(gespeichert.getDeltaPointsX100()).isEqualTo(-13_500);
    }

    @Test
    void korrekturMitPunktenDirekt() {
        service.correct(QR, shop, null, 5_000L, "Kasse 1");
        assertThat(cc.getPointsX100()).isEqualTo(5_000);
        assertThat(letzteGespeicherte().getAmountCents()).isNull();
    }

    @Test
    void korrekturBrauchtGenauEineAngabe() {
        assertThatThrownBy(() -> service.correct(QR, shop, 100L, 100L, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entweder");
        assertThatThrownBy(() -> service.correct(QR, shop, null, null, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entweder");
    }

    @Test
    void zuruecknehmenDrehtDieBuchungUm() {
        service.earn(QR, shop, 14_500, "Kasse 1");
        PointsBooking original = letzteGespeicherte();
        when(bookingRepo.findById(original.getId())).thenReturn(Optional.of(original));

        service.undo(QR, shop, original.getId(), "Kasse 1");

        assertThat(cc.getPointsX100()).isZero();
        PointsBooking gegenbuchung = letzteGespeicherte();
        assertThat(gegenbuchung.getKind()).isEqualTo(BookingKind.REVERSAL);
        assertThat(gegenbuchung.getReversalOfId()).isEqualTo(original.getId());
        assertThat(original.istZurueckgenommen()).isTrue();
    }

    @Test
    void zweimalZuruecknehmenScheitert() {
        // Der Fall, der im Laden wirklich passiert: zwei Kassen, beide sehen
        // dieselbe Buchung, beide druecken zurueck.
        service.earn(QR, shop, 14_500, "Kasse 1");
        PointsBooking original = letzteGespeicherte();
        when(bookingRepo.findById(original.getId())).thenReturn(Optional.of(original));

        service.undo(QR, shop, original.getId(), "Kasse 1");

        assertThatThrownBy(() -> service.undo(QR, shop, original.getId(), "Kasse 2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bereits zurueckgenommen");
    }

    @Test
    void zuruecknehmenEinerRuecknahmeScheitert() {
        service.earn(QR, shop, 14_500, "Kasse 1");
        PointsBooking original = letzteGespeicherte();
        when(bookingRepo.findById(original.getId())).thenReturn(Optional.of(original));

        service.undo(QR, shop, original.getId(), "Kasse 1");
        PointsBooking gegenbuchung = letzteGespeicherte();
        when(bookingRepo.findById(gegenbuchung.getId()))
                .thenReturn(Optional.of(gegenbuchung));

        assertThatThrownBy(() -> service.undo(QR, shop, gegenbuchung.getId(), "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Gegenbuchung");
    }

    @Test
    void zuruecknehmenEinerEinloesungGibtDieBelohnungZurueck() {
        service.earn(QR, shop, 30_000, "Kasse 1");
        when(rewardService.getByIdAndCard("RW-KUCHEN", card)).thenReturn(kuchen);
        service.redeem(QR, shop, "RW-KUCHEN", "Kasse 1");
        PointsBooking einloesung = letzteGespeicherte();
        when(bookingRepo.findById(einloesung.getId())).thenReturn(Optional.of(einloesung));

        service.undo(QR, shop, einloesung.getId(), "Kasse 1");

        assertThat(cc.getPointsX100()).isEqualTo(30_000);
        assertThat(cc.getTotalRewards()).isZero();
    }

    @Test
    void stempelkarteAmPunkteWegScheitert() {
        Card stempelkarte = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");
        when(cardRepo.findById("CARD-1")).thenReturn(Optional.of(stempelkarte));

        assertThatThrownBy(() -> service.earn(QR, shop, 1_000, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sammelt Stempel");
    }

    @Test
    void fremdeKarteScheitert() {
        Shop andererShop = mock(Shop.class);
        when(andererShop.getId()).thenReturn("SHOP-2");

        assertThatThrownBy(() -> service.earn(QR, andererShop, 1_000, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gehoert nicht");
    }

    @Test
    void betragUeberGrenzeScheitert() {
        assertThatThrownBy(() -> service.earn(QR, shop, 10_000_000L, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Betrag");
    }

    @Test
    void negativerBetragBeimBuchenScheitert() {
        assertThatThrownBy(() -> service.earn(QR, shop, -100, "Kasse 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Korrektur");
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `./mvnw -o test -Dtest=PunkteBuchungTest`
Expected: FAIL, `cannot find symbol: class PointsService`

- [ ] **Step 3: Prüfen, ob `CustomerCardRepository` die benötigte Methode hat**

Run:

```bash
grep -n "findByCustomerIdAndCardId\|findByCustomer" src/main/java/com/example/stemplekarte/repository/CustomerCardRepository.java
```

Fehlt sie, ergänzen:

```java
    // Kundenkarte ueber die beiden IDs aus dem QR-Code. @EntityGraph, weil
    // der Aufrufer Karte und Kunde braucht und open-in-view aus ist.
    @EntityGraph(attributePaths = {"card", "customer"})
    Optional<CustomerCard> findByCustomerIdAndCardId(String customerId, String cardId);
```

Importe: `org.springframework.data.jpa.repository.EntityGraph`, `java.util.Optional`.

- [ ] **Step 4: `PointsService` implementieren**

`src/main/java/com/example/stemplekarte/service/PointsService.java`:

```java
package com.example.stemplekarte.service;

import com.example.stemplekarte.model.*;
import com.example.stemplekarte.repository.CardRepository;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.repository.PointsBookingRepository;
import com.example.stemplekarte.repository.ScanLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Punktebuchungen. Jeder Weg aendert den Stand und schreibt genau eine
 * Buchung, in derselben Transaktion.
 *
 * Bewusst getrennt von CustomerService: der traegt den Stempel-Weg, der in
 * echten Laeden laeuft. Zwei Zaehler in einer Klasse waeren zwei Gruende,
 * dieselbe Datei anzufassen.
 *
 * Der ScanLog wird bei EARN und REDEEM mitgeschrieben, damit Stossszeiten
 * und aktive Kunden ueber beide Kartentypen stimmen. Korrektur und
 * Ruecknahme schreiben keinen - sie sind kein Kundenbesuch. Wie beim
 * Stempel-Weg darf ein Fehler dabei die Buchung nicht abbrechen.
 */
@Service
public class PointsService {

    private static final Logger log = LoggerFactory.getLogger(PointsService.class);

    private final CustomerCardRepository customerCardRepo;
    private final CardRepository cardRepo;
    private final PointsBookingRepository bookingRepo;
    private final ScanLogRepository scanLogRepo;
    private final RewardService rewardService;

    public PointsService(CustomerCardRepository customerCardRepo,
                         CardRepository cardRepo,
                         PointsBookingRepository bookingRepo,
                         ScanLogRepository scanLogRepo,
                         RewardService rewardService) {
        this.customerCardRepo = customerCardRepo;
        this.cardRepo = cardRepo;
        this.bookingRepo = bookingRepo;
        this.scanLogRepo = scanLogRepo;
        this.rewardService = rewardService;
    }

    // ── Ausgabe-Typen ─────────────────────────────────────────────────────
    // Bewusst OHNE JPA-Entitaeten. Sie werden innerhalb der Transaktion
    // gefuellt; der Controller reicht sie nur noch weiter. Ein Reward oder
    // eine CustomerCard hier durchzureichen hiesse, dem Controller einen
    // Lazy-Proxy in die Hand zu druecken - und mit open-in-view: false ist
    // die Session dort zu. Genau so ist am 12.09. jeder Scan nach dem
    // gesetzten Stempel abgestuerzt.

    public record RewardView(String id, String name, long costPointsX100,
                             String costText, boolean bezahlbar, long fehlendX100) {
        static RewardView von(Reward r, long standX100) {
            long fehlend = Math.max(0, r.getCostPointsX100() - standX100);
            return new RewardView(r.getId(), r.getName(), r.getCostPointsX100(),
                    PointsMath.formatiere(r.getCostPointsX100()), fehlend == 0, fehlend);
        }
    }

    /** Ohne staffLabel kaeme nicht heraus, welches Geraet gebucht hat. MIT
     *  dem Token waere die Zugangsberechtigung im Frontend ablesbar - der
     *  Token ist bei StaffToken zugleich Primaerschluessel und Berechtigung. */
    public record BookingView(String id, String kind, long deltaPointsX100,
                              String deltaText, Long amountCents, String rewardName,
                              String staffLabel, Instant createdAt) {
        static BookingView von(PointsBooking b) {
            if (b == null) return null;
            return new BookingView(b.getId(), b.getKind().name(), b.getDeltaPointsX100(),
                    PointsMath.formatiere(b.getDeltaPointsX100()), b.getAmountCents(),
                    b.getRewardName(), b.getStaffLabel(), b.getCreatedAt());
        }
    }

    /** Alles, was der Scanner nach dem Scannen braucht, um zu entscheiden,
     *  welche Oberflaeche er zeigt. pointsRounding ist dabei nicht optional:
     *  ohne sie kann die Live-Vorschau im Scanner nicht dasselbe rechnen wie
     *  die Buchung, und genau dieses Vertrauen soll sie herstellen. */
    public record PointsState(
            String customerCardId, String customerName,
            String cardId, String cardName, CardType type,
            long pointsX100, String pointsText,
            int stamps, int rewardThreshold, String rewardText,
            Integer pointsPerEuroX100, String pointsRounding,
            List<RewardView> katalog, RewardView ziel, long fehlendX100,
            String fehlendText, BookingView letzteBuchung) {}

    /** Ergebnis einer Buchung. */
    public record PointsResult(
            String customerCardId, String customerName,
            String cardId, String cardName,
            long pointsX100, String pointsText,
            BookingView booking, RewardView ziel,
            long fehlendX100, String fehlendText,
            boolean neuesZielErreicht, List<RewardView> katalog) {}

    // ── Zustand ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PointsState state(String qrPayload, Shop shop) {
        QrPayload qr = QrPayload.parse(qrPayload);
        Card card = ladeKarte(qr.cardId(), shop);
        CustomerCard cc = ladeKundenkarte(qr, card);

        if (!card.isPoints()) {
            // Stempelkarte: der Scanner braucht nur den Typ und den Stand.
            return new PointsState(cc.getId(), cc.getCustomer().getName(),
                    card.getId(), card.getName(), CardType.STAMP,
                    0, "0", cc.getStamps(), card.getRewardThreshold(), card.getRewardText(),
                    null, null, List.of(), null, 0, "0", null);
        }

        long stand = cc.getPointsX100();
        List<Reward> katalog = rewardService.list(card);
        Reward ziel = RewardService.naechstesZiel(katalog, stand);
        long fehlend = fehlend(ziel, stand);

        return new PointsState(cc.getId(), cc.getCustomer().getName(),
                card.getId(), card.getName(), CardType.POINTS,
                stand, PointsMath.formatiere(stand),
                0, 0, null,
                card.getPointsPerEuroX100(), card.getPointsRounding().name(),
                katalog.stream().map(r -> RewardView.von(r, stand)).toList(),
                ziel != null ? RewardView.von(ziel, stand) : null,
                fehlend, PointsMath.formatiere(fehlend),
                BookingView.von(letzteBuchung(cc.getId())));
    }

    // ── Buchen ────────────────────────────────────────────────────────────

    @Transactional
    public PointsResult earn(String qrPayload, Shop shop, long amountCents, String staffLabel) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException(
                    "Betrag muss groesser als null sein. Fuer einen Abzug die Korrektur benutzen.");
        }
        QrPayload qr = QrPayload.parse(qrPayload);
        Card card = ladePunktekarte(qr.cardId(), shop);
        CustomerCard cc = ladeKundenkarte(qr, card);

        int kurs = card.getPointsPerEuroX100();
        long punkte = PointsMath.punkteFuer(amountCents, kurs, card.getPointsRounding());

        long vorher = cc.getPointsX100();
        long gebucht = cc.addPoints(punkte);
        customerCardRepo.save(cc);

        PointsBooking booking = bookingRepo.save(
                PointsBooking.earn(cc, shop.getId(), gebucht, amountCents, kurs, staffLabel));

        scanLogSchreiben(shop, card, cc, 0);

        log.info("[PUNKTE] EARN karte={} betrag={} punkte={} stand={}",
                cc.getId(), amountCents, gebucht, cc.getPointsX100());

        return ergebnis(card, cc, booking, vorher);
    }

    @Transactional
    public PointsResult redeem(String qrPayload, Shop shop, String rewardId, String staffLabel) {
        QrPayload qr = QrPayload.parse(qrPayload);
        Card card = ladePunktekarte(qr.cardId(), shop);
        CustomerCard cc = ladeKundenkarte(qr, card);

        Reward reward = rewardService.getByIdAndCard(rewardId, card);
        if (!reward.isActive()) {
            throw new IllegalArgumentException("Diese Praemie gibt es nicht mehr");
        }
        if (!cc.kannBezahlen(reward.getCostPointsX100())) {
            throw new IllegalArgumentException(
                    "Nicht genug Punkte: %s von %s noetig".formatted(
                            PointsMath.formatiere(cc.getPointsX100()),
                            PointsMath.formatiere(reward.getCostPointsX100())));
        }

        long vorher = cc.getPointsX100();
        cc.addPoints(-reward.getCostPointsX100());
        cc.zaehleBelohnung();
        customerCardRepo.save(cc);

        PointsBooking booking = bookingRepo.save(
                PointsBooking.redeem(cc, shop.getId(), reward, staffLabel));

        scanLogSchreiben(shop, card, cc, 1);

        log.info("[PUNKTE] REDEEM karte={} praemie={} kosten={} stand={}",
                cc.getId(), reward.getName(), reward.getCostPointsX100(), cc.getPointsX100());

        return ergebnis(card, cc, booking, vorher);
    }

    @Transactional
    public PointsResult correct(String qrPayload, Shop shop,
                                Long amountCents, Long pointsX100, String staffLabel) {
        boolean hatBetrag = amountCents != null && amountCents != 0;
        boolean hatPunkte = pointsX100 != null && pointsX100 != 0;
        if (hatBetrag == hatPunkte) {
            throw new IllegalArgumentException(
                    "Korrektur braucht entweder einen Betrag oder eine Punktzahl, nicht beides");
        }

        QrPayload qr = QrPayload.parse(qrPayload);
        Card card = ladePunktekarte(qr.cardId(), shop);
        CustomerCard cc = ladeKundenkarte(qr, card);

        Integer kurs = hatBetrag ? card.getPointsPerEuroX100() : null;
        long delta = hatBetrag
                ? PointsMath.punkteFuer(amountCents, kurs, card.getPointsRounding())
                : pointsX100;

        long vorher = cc.getPointsX100();
        long gebucht = cc.addPoints(delta);
        customerCardRepo.save(cc);

        PointsBooking booking = bookingRepo.save(PointsBooking.correction(
                cc, shop.getId(), gebucht, hatBetrag ? amountCents : null, kurs, staffLabel));

        log.info("[PUNKTE] CORRECTION karte={} delta={} stand={} von={}",
                cc.getId(), gebucht, cc.getPointsX100(), staffLabel);

        return ergebnis(card, cc, booking, vorher);
    }

    @Transactional
    public PointsResult undo(String qrPayload, Shop shop, String bookingId, String staffLabel) {
        QrPayload qr = QrPayload.parse(qrPayload);
        Card card = ladePunktekarte(qr.cardId(), shop);
        CustomerCard cc = ladeKundenkarte(qr, card);

        PointsBooking original = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new NoSuchElementException("Buchung nicht gefunden"));

        // Fremde Buchung: darf nicht ueber eine andere Karte zurueckgenommen
        // werden, sonst greift ein Laden in die Karte eines anderen.
        if (!original.getCustomerCardId().equals(cc.getId())
                || !original.getShopId().equals(shop.getId())) {
            throw new IllegalArgumentException("Buchung gehoert nicht zu dieser Karte");
        }
        if (original.istZurueckgenommen()) {
            throw new IllegalArgumentException("Diese Buchung wurde bereits zurueckgenommen");
        }
        if (original.getKind() == BookingKind.REVERSAL) {
            throw new IllegalArgumentException(
                    "Eine Gegenbuchung laesst sich nicht zuruecknehmen. Neu buchen.");
        }

        long vorher = cc.getPointsX100();
        long gebucht = cc.addPoints(-original.getDeltaPointsX100());
        if (original.getKind() == BookingKind.REDEEM) {
            // Die Praemie war nie eingeloest, also zaehlt sie auch nicht.
            cc.nimmBelohnungZurueck();
        }
        customerCardRepo.save(cc);

        original.markiereAlsZurueckgenommen();
        bookingRepo.save(original);

        PointsBooking booking = bookingRepo.save(
                PointsBooking.reversal(cc, shop.getId(), original, gebucht, staffLabel));

        log.info("[PUNKTE] REVERSAL karte={} zuBuchung={} delta={} stand={}",
                cc.getId(), original.getId(), gebucht, cc.getPointsX100());

        return ergebnis(card, cc, booking, vorher);
    }

    // ── Helfer ────────────────────────────────────────────────────────────

    private PointsResult ergebnis(Card card, CustomerCard cc,
                                  PointsBooking booking, long standVorher) {
        long stand = cc.getPointsX100();
        List<Reward> katalog = rewardService.list(card);
        Reward ziel = RewardService.naechstesZiel(katalog, stand);
        long fehlend = fehlend(ziel, stand);

        // "Neues Ziel erreicht" heisst: es ist jetzt eine Praemie bezahlbar,
        // die es vorher nicht war. Nur dann meldet sich der Pass - sonst
        // wuerde die Sperrbildschirm-Meldung bei jeder Buchung aufpoppen und
        // zum Rauschen werden.
        long bezahlbarVorher = katalog.stream()
                .filter(r -> r.getCostPointsX100() <= standVorher).count();
        long bezahlbarJetzt = katalog.stream()
                .filter(r -> r.getCostPointsX100() <= stand).count();

        // Alles Abbilden passiert HIER, innerhalb der Transaktion. Was der
        // Controller bekommt, ist frei von Lazy-Proxies.
        return new PointsResult(
                cc.getId(), cc.getCustomer().getName(),
                card.getId(), card.getName(),
                stand, PointsMath.formatiere(stand),
                BookingView.von(booking),
                ziel != null ? RewardView.von(ziel, stand) : null,
                fehlend, PointsMath.formatiere(fehlend),
                bezahlbarJetzt > bezahlbarVorher,
                katalog.stream().map(r -> RewardView.von(r, stand)).toList());
    }

    private long fehlend(Reward ziel, long standX100) {
        if (ziel == null) return 0;
        return Math.max(0, ziel.getCostPointsX100() - standX100);
    }

    private PointsBooking letzteBuchung(String customerCardId) {
        return bookingRepo.findTop10ByCustomerCardIdOrderByCreatedAtDesc(customerCardId)
                .stream()
                .filter(b -> !b.istZurueckgenommen() && b.getKind() != BookingKind.REVERSAL)
                .findFirst()
                .orElse(null);
    }

    private Card ladeKarte(String cardId, Shop shop) {
        Card card = cardRepo.findById(cardId)
                .orElseThrow(() -> new NoSuchElementException("Karte nicht gefunden"));
        if (!card.getShop().getId().equals(shop.getId())) {
            throw new IllegalArgumentException("Diese Karte gehoert nicht zu deinem Shop");
        }
        if (!card.isActive()) {
            throw new IllegalArgumentException("Diese Karte ist nicht mehr aktiv");
        }
        return card;
    }

    private Card ladePunktekarte(String cardId, Shop shop) {
        Card card = ladeKarte(cardId, shop);
        if (!card.isPoints()) {
            throw new IllegalArgumentException("Diese Karte sammelt Stempel, keine Punkte");
        }
        return card;
    }

    private CustomerCard ladeKundenkarte(QrPayload qr, Card card) {
        return customerCardRepo.findByCustomerIdAndCardId(qr.customerId(), card.getId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Dieser Kunde hat die Karte noch nicht"));
    }

    private void scanLogSchreiben(Shop shop, Card card, CustomerCard cc, int rewardsEarned) {
        // Wie beim Stempel-Weg: schlaegt das Protokoll fehl, darf die Buchung
        // NICHT scheitern. stampsAdded bleibt 0, damit sich Stempel- und
        // Punktzahlen in der Statistik nicht vermischen.
        try {
            scanLogRepo.save(ScanLog.create(shop.getId(), card.getId(),
                    cc.getCustomer().getId(), 0, rewardsEarned));
        } catch (Exception e) {
            log.warn("ScanLog konnte nicht gespeichert werden: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Die zwei fehlenden `CustomerCard`-Methoden ergänzen**

`redeemReward` zieht Stempel ab und zählt hoch — für Punkte braucht es das Hochzählen allein. In `CustomerCard.java` ergänzen:

```java
    /** Belohnung zaehlen, ohne am Stempelstand zu ruehren. Punktekarten
     *  ziehen den Preis ueber addPoints ab, nicht ueber eine Schwelle. */
    public void zaehleBelohnung() {
        this.totalRewards++;
        this.updatedAt = Instant.now();
    }

    /** Ruecknahme einer Einloesung. Geht nie unter null - sonst stuende auf
     *  einer Karte eine negative Zahl eingeloester Praemien. */
    public void nimmBelohnungZurueck() {
        this.totalRewards = Math.max(0, this.totalRewards - 1);
        this.updatedAt = Instant.now();
    }
```

- [ ] **Step 6: Tests laufen lassen**

Run: `./mvnw -o test -Dtest=PunkteBuchungTest`
Expected: PASS, 16 Tests

- [ ] **Step 7: Volle Suite**

Run: `./mvnw -o test`
Expected: PASS, alle Klassen grün

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/stemplekarte/service/PointsService.java \
        src/main/java/com/example/stemplekarte/model/CustomerCard.java \
        src/main/java/com/example/stemplekarte/repository/CustomerCardRepository.java \
        src/test/java/com/example/stemplekarte/service/PunkteBuchungTest.java
git commit -m "feat: Punkte buchen, einloesen, korrigieren, zuruecknehmen"
```

---

### Task 8: Endpunkte

**Files:**
- Create: `src/main/java/com/example/stemplekarte/controller/PointsController.java`
- Modify: `src/main/java/com/example/stemplekarte/controller/ScanController.java` (neuer Endpunkt `/api/scan/state`)
- Modify: `src/main/java/com/example/stemplekarte/controller/ShopController.java` (Punktekarte anlegen, Katalog pflegen)
- Test: `src/test/java/com/example/stemplekarte/controller/PunkteControllerTest.java`

**Interfaces:**
- Consumes: `PointsService` (Task 7), `RewardService` (Task 4), `CardService.createPoints` (Task 3), `WalletNotifier` (Task 6).
- Produces: die fünf Routen aus Abschnitt 5 der Spec plus `POST /api/cards/points`, `GET/POST/DELETE /api/cards/{cardId}/rewards`.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`src/test/java/com/example/stemplekarte/controller/PunkteControllerTest.java`:

```java
package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.StaffToken;
import com.example.stemplekarte.security.StaffTokenFilter;
import com.example.stemplekarte.service.PointsService;
import com.example.stemplekarte.wallet.WalletNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Zwei Dinge, die im Laden wehtun, wenn sie fehlen.
 *
 * Erstens: ein Aufruf ohne gueltiges Staff-Token muss 401 geben, nicht 500.
 * Beim Sperren-Knopf im Admin-Panel hat genau das eine Sitzung gekostet -
 * eine fehlende Annotation liess einen sauberen Fehlerfall als generischen
 * 500 ankommen, und im Panel war nicht zu unterscheiden, ob der Server
 * kaputt ist oder die Anmeldung abgelaufen.
 *
 * Zweitens: das Staff-Token darf die Antwort nicht verlassen. Sein Wert ist
 * die Zugangsberechtigung; in der Buchungsliste hat er nichts zu suchen.
 */
class PunkteControllerTest {

    private Authentication authMit(Shop shop, String label) {
        StaffToken staff = mock(StaffToken.class);
        when(staff.getShop()).thenReturn(shop);
        when(staff.getLabel()).thenReturn(label);
        return new UsernamePasswordAuthenticationToken(
                new StaffTokenFilter.StaffPrincipal(staff), null, List.of());
    }

    @Test
    void ohneStaffToken_gibt401() {
        PointsController controller = new PointsController(
                mock(PointsService.class), mock(WalletNotifier.class));

        assertThatThrownBy(() -> controller.earn(
                new PointsController.EarnRequest("{}", 1000), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void buchtMitDemGeraetenamen_nichtMitDemToken() {
        PointsService service = mock(PointsService.class);
        Shop shop = mock(Shop.class);
        when(shop.getId()).thenReturn("SHOP-1");

        PointsController controller = new PointsController(service, mock(WalletNotifier.class));

        // Der Aufruf scheitert an der fehlenden Antwort des Mocks - hier
        // zaehlt nur, WOMIT der Dienst gerufen wurde.
        try {
            controller.earn(new PointsController.EarnRequest("{}", 14_500),
                    authMit(shop, "Kasse 1"));
        } catch (Exception ignoriert) {
            // Mock liefert null, das Abbilden der Antwort scheitert danach.
        }

        verify(service).earn(anyString(), any(Shop.class), anyLong(),
                org.mockito.ArgumentMatchers.eq("Kasse 1"));
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `./mvnw -o test -Dtest=PunkteControllerTest`
Expected: FAIL, `cannot find symbol: class PointsController`

- [ ] **Step 3: `PointsController` anlegen**

`src/main/java/com/example/stemplekarte/controller/PointsController.java`:

```java
package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.security.StaffTokenFilter;
import com.example.stemplekarte.service.PointsService;
import com.example.stemplekarte.wallet.WalletNotifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Punktebuchungen. Alle Routen brauchen den X-Staff-Token-Header, wie
 * /api/scan.
 *
 * POST auch beim Abfragen des Zustands: der QR-Inhalt hat nichts in einer
 * URL und damit in Server-Logs zu suchen.
 */
@Tag(name = "Punkte", description = "Punkte buchen und einloesen - braucht X-Staff-Token")
@RestController
@RequestMapping("/api/points")
public class PointsController {

    private final PointsService service;
    private final WalletNotifier notifier;

    public PointsController(PointsService service, WalletNotifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    // ── Anfragen ──────────────────────────────────────────────────────────

    public record EarnRequest(@NotBlank String qrPayload, long amountCents) {}
    public record RedeemRequest(@NotBlank String qrPayload, @NotBlank String rewardId) {}
    public record CorrectRequest(@NotBlank String qrPayload, Long amountCents, Long pointsX100) {}
    public record UndoRequest(@NotBlank String qrPayload, @NotBlank String bookingId) {}

    // ── Antworten ─────────────────────────────────────────────────────────
    //
    // Die Ansichtstypen liegen im PointsService und werden dort INNERHALB
    // der Transaktion gefuellt. Der Controller reicht sie nur weiter und
    // fasst selbst keine Entity mehr an - mit open-in-view: false wuerde
    // jeder Zugriff hier auf eine geschlossene Session treffen.

    public record PointsResponse(String customerCardId, String customerName,
                                 String cardId, String cardName,
                                 long pointsX100, String pointsText,
                                 String zielName, long fehlendX100, String fehlendText,
                                 boolean neuesZielErreicht,
                                 List<PointsService.RewardView> katalog,
                                 PointsService.BookingView letzteBuchung) {}

    // ── Routen ────────────────────────────────────────────────────────────

    @Operation(summary = "Einkauf buchen")
    @PostMapping("/earn")
    public PointsResponse earn(@Valid @RequestBody EarnRequest req, Authentication auth) {
        StaffToken staff = staffAus(auth);
        var ergebnis = service.earn(req.qrPayload(), staff.shop(), req.amountCents(), staff.label());
        melden(ergebnis);
        return antwort(ergebnis);
    }

    @Operation(summary = "Praemie einloesen")
    @PostMapping("/redeem")
    public PointsResponse redeem(@Valid @RequestBody RedeemRequest req, Authentication auth) {
        StaffToken staff = staffAus(auth);
        var ergebnis = service.redeem(req.qrPayload(), staff.shop(), req.rewardId(), staff.label());
        melden(ergebnis);
        return antwort(ergebnis);
    }

    @Operation(summary = "Punkte von Hand korrigieren")
    @PostMapping("/correct")
    public PointsResponse correct(@Valid @RequestBody CorrectRequest req, Authentication auth) {
        StaffToken staff = staffAus(auth);
        var ergebnis = service.correct(req.qrPayload(), staff.shop(),
                req.amountCents(), req.pointsX100(), staff.label());
        melden(ergebnis);
        return antwort(ergebnis);
    }

    @Operation(summary = "Buchung zuruecknehmen")
    @PostMapping("/undo")
    public PointsResponse undo(@Valid @RequestBody UndoRequest req, Authentication auth) {
        StaffToken staff = staffAus(auth);
        var ergebnis = service.undo(req.qrPayload(), staff.shop(), req.bookingId(), staff.label());
        melden(ergebnis);
        return antwort(ergebnis);
    }

    // ── Helfer ────────────────────────────────────────────────────────────

    private record StaffToken(Shop shop, String label) {}

    private StaffToken staffAus(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal p)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }
        return new StaffToken(p.staff().getShop(), p.staff().getLabel());
    }

    private void melden(PointsService.PointsResult e) {
        notifier.nachPunkteAenderung(e.customerCardId(), e.pointsX100(),
                e.ziel() != null ? e.ziel().name() : null, e.fehlendX100());
        if (e.neuesZielErreicht()) {
            notifier.googleKarteVoll(e.customerCardId(), "Praemie verfuegbar");
        }
    }

    /**
     * Reines Umhaengen. Kein Datenbankzugriff, kein Entity-Zugriff: alles
     * ist im Dienst innerhalb der Transaktion fertig abgebildet worden.
     */
    private PointsResponse antwort(PointsService.PointsResult e) {
        return new PointsResponse(
                e.customerCardId(), e.customerName(),
                e.cardId(), e.cardName(),
                e.pointsX100(), e.pointsText(),
                e.ziel() != null ? e.ziel().name() : null,
                e.fehlendX100(), e.fehlendText(),
                e.neuesZielErreicht(),
                e.katalog(), e.booking());
    }
}
```

- [ ] **Step 4: `/api/scan/state` im `ScanController` ergänzen**

```java
    public record StateRequest(@NotBlank String qrPayload) {}

    @Operation(summary = "QR aufloesen: welcher Kartentyp, welcher Stand",
            description = "Steuert, welche Oberflaeche der Scanner zeigt. "
                    + "Erfordert X-Staff-Token.")
    @PostMapping("/state")
    public PointsService.PointsState state(@Valid @RequestBody StateRequest req,
                                           Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof StaffTokenFilter.StaffPrincipal p)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kein gueltiger Staff-Token");
        }
        return pointsService.state(req.qrPayload(), p.staff().getShop());
    }
```

`PointsService pointsService` in den Konstruktor aufnehmen. `ScanControllerTest` entsprechend um ein `mock(PointsService.class)` erweitern.

- [ ] **Step 5: Punktekarte anlegen und Katalog pflegen im `ShopController`**

```java
    public record CreatePointsCardRequest(
            @NotBlank String name,
            @NotBlank String description,
            int pointsPerEuroX100,
            String pointsRounding,
            String colorBackground, String colorForeground, String colorLabel,
            String logoUrl, String heroImageUrl
    ) {}

    @Operation(summary = "Neue Punktekarte erstellen")
    @PostMapping("/cards/points")
    public CardResponse createPointsCard(@Valid @RequestBody CreatePointsCardRequest req,
                                         Authentication auth) {
        Shop shop = currentShop(auth);
        PointsRounding rundung = req.pointsRounding() == null
                ? PointsRounding.GENAU
                : PointsRounding.valueOf(req.pointsRounding());
        Card card = cardService.createPoints(shop, req.name(), req.description(),
                req.pointsPerEuroX100(), rundung);
        card.updateColors(req.colorBackground(), req.colorForeground(), req.colorLabel());
        if (req.logoUrl() != null) card.setLogoUrl(req.logoUrl());
        if (req.heroImageUrl() != null) card.setHeroImageUrl(req.heroImageUrl());
        cardService.save(card);
        return CardResponse.from(card);
    }

    public record RewardRequest(@NotBlank String name, long costPointsX100) {}
    public record RewardResponse(String id, String name, long costPointsX100,
                                 String costText, int sortOrder) {
        static RewardResponse from(Reward r) {
            return new RewardResponse(r.getId(), r.getName(), r.getCostPointsX100(),
                    PointsMath.formatiere(r.getCostPointsX100()), r.getSortOrder());
        }
    }

    @Operation(summary = "Praemien einer Karte auflisten")
    @GetMapping("/cards/{cardId}/rewards")
    public List<RewardResponse> listRewards(@PathVariable String cardId, Authentication auth) {
        Card card = cardService.getByIdAndShop(cardId, currentShop(auth));
        return rewardService.list(card).stream().map(RewardResponse::from).toList();
    }

    @Operation(summary = "Praemie hinzufuegen")
    @PostMapping("/cards/{cardId}/rewards")
    public RewardResponse addReward(@PathVariable String cardId,
                                    @Valid @RequestBody RewardRequest req,
                                    Authentication auth) {
        Card card = cardService.getByIdAndShop(cardId, currentShop(auth));
        return RewardResponse.from(rewardService.add(card, req.name(), req.costPointsX100()));
    }

    @Operation(summary = "Praemie entfernen")
    @DeleteMapping("/cards/{cardId}/rewards/{rewardId}")
    public ResponseEntity<Map<String, String>> deleteReward(@PathVariable String cardId,
                                                            @PathVariable String rewardId,
                                                            Authentication auth) {
        Card card = cardService.getByIdAndShop(cardId, currentShop(auth));
        rewardService.deactivate(rewardId, card);
        return ResponseEntity.ok(Map.of("message", "Praemie entfernt"));
    }
```

`CardResponse` um `type` und die Punkte-Felder erweitern:

```java
    public record CardResponse(
            String id, String name, String description,
            int rewardThreshold, String rewardText,
            String walletStyle, String stampIconType, String stampPreset,
            String stampColor, String emptyStampStyle, String stampIconUrl,
            String colorBackground, String colorForeground, String colorLabel,
            String logoUrl, String heroImageUrl,
            String type, Integer pointsPerEuroX100, String pointsRounding
    ) {
        static CardResponse from(Card c) {
            return new CardResponse(
                    c.getId(), c.getName(), c.getDescription(),
                    c.getRewardThreshold(), c.getRewardText(),
                    c.getWalletStyle(), c.getStampIconType(), c.getStampPreset(),
                    c.getStampColor(), c.getEmptyStampStyle(),
                    c.getStampIconUrl() != null ? c.getStampIconUrl() : "",
                    c.getColorBackground(), c.getColorForeground(), c.getColorLabel(),
                    c.getLogoUrl() != null ? c.getLogoUrl() : "",
                    c.getHeroImageUrl() != null ? c.getHeroImageUrl() : "",
                    c.getType().name(),
                    c.getPointsPerEuroX100(),
                    c.isPoints() ? c.getPointsRounding().name() : null
            );
        }
    }
```

`RewardService rewardService` in den Konstruktor von `ShopController` aufnehmen.

- [ ] **Step 6: Tests laufen lassen**

Run: `./mvnw -o test`
Expected: PASS, alle Klassen grün

- [ ] **Step 7: Von Hand gegen einen laufenden Server prüfen**

```bash
./mvnw -o spring-boot:run
```

In einem zweiten Fenster, mit einem Staff-Token aus der Laden-App:

```bash
curl -s -X POST localhost:8080/api/scan/state \
  -H "Content-Type: application/json" -H "X-Staff-Token: <TOKEN>" \
  -d '{"qrPayload":"{\"cid\":\"CUST-X\",\"cardId\":\"CARD-Y\"}"}' | jq
```

Expected: JSON mit `"type": "STAMP"` für eine bestehende Karte — der Beweis, dass alte Karten unverändert als Stempelkarten gelten.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/stemplekarte/controller/PointsController.java \
        src/main/java/com/example/stemplekarte/controller/ScanController.java \
        src/main/java/com/example/stemplekarte/controller/ShopController.java \
        src/test/java/com/example/stemplekarte/controller/PunkteControllerTest.java \
        src/test/java/com/example/stemplekarte/controller/ScanControllerTest.java
git commit -m "feat: Endpunkte fuer Punktekarten und Praemien-Katalog"
```

---

## Abschluss Teil 1

Nach Task 8 kann das Backend alles, was die Punktekarte ausmacht — bedienbar über die API, ohne dass sich für Stempelkarten irgendetwas geändert hat.

**Vor dem Push:**

```bash
./mvnw -o test
```

**Nach dem Deploy prüfen** — nicht an `actuator/health`, das beweist nur einen laufenden Prozess:

```bash
curl -s https://<render-url>/v3/api-docs | jq '.paths | keys | map(select(startswith("/api/points")))'
```

Expected: die vier Punkte-Routen. Render braucht dafür regelmäßig 10 bis 20 Minuten.

**Danach:** `2026-09-21-punktekarte-2-laden-app.md`
