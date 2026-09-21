# Punktekarte, Teil 3: Darstellung — Umsetzungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der Kunde sieht seine Punkte und seine Ziele — auf der Apple-Wallet-Karte, in Google Wallet und auf der Kartenseite im Browser. Der Laden sieht in der Statistik Umsatz, vergebene Punkte, eingelöste Prämien und den ausstehenden Punktebestand.

**Architecture:** Der Feldbau des Apple-Passes wird aus `buildPass` herausgezogen, damit er ohne Signier-Zertifikate testbar ist; erst dann kommt der Punkte-Zweig dazu. Alles Neue liegt in einem Zweig, der nur bei `type == POINTS` betreten wird — der Stempel-Pass bleibt Feld für Feld identisch. Google braucht keine neue Technik, nur andere Werte in `loyaltyPoints` und `textModulesData`.

**Tech Stack:** Spring Boot 3.5, Java 21, jpasskit 0.4.0 (`de.brendamour.jpasskit`), google-api-services-walletobjects, vom Backend gerendertes HTML mit SSE.

**Repo:** `C:\Project SK\Stemplekarte`
**Spec:** `docs/specs/2026-09-21-punktekarte-design.md`
**Setzt voraus:** Teil 1 und Teil 2 sind deployt.

## Global Constraints

- **Der Stempel-Pass bleibt Feld für Feld identisch.** Das ist die härteste Regel dieses Plans. Der Kommentar im Code warnt zu Recht: ein unpassendes Feld (damals `groupingIdentifier`) hat iOS schon einmal den aktualisierten Pass verwerfen lassen — Karte installiert, aktualisiert aber nie. Diese Karten liegen gerade in echten Läden.
- **Rückseitenfelder gibt es im Pass bisher gar keine.** Der Katalog wird das erste. Nur bei Punktekarten.
- **Neue Spalten nullable oder mit Default.** `ddl-auto: update`.
- **`open-in-view: false`.** Lazy-Beziehungen ausserhalb einer Transaktion fliegen um die Ohren.
- **Kommentare auf Deutsch, sie erklären das Warum.** Umlaute darin umschrieben, in Nutzertexten nicht.
- **Keine Geviertstriche** in Texten, die Nutzer sehen.
- **Die Kundenseite ist deutschsprachig**, weil das Backend noch keine Übersetzungstabelle hat (siehe `2026-08-30-arabisch-rtl-sprache-design.md`, unerledigt). Die Punkte-Texte kommen dort auf Deutsch dazu, wie die Stempel-Texte heute. Kein Anlass, die Arabisch-Spec hier mitzuerledigen.
- **Vor jedem Push `./mvnw -o test`.** Render baut mit `-DskipTests`.
- **Nicht pushen, ohne dass danach gefragt wurde.**

---

### Task 1: Feldbau herausziehen und absichern

Ohne diesen Schritt ist der Pass nicht testbar: `buildPass` signiert am Ende und braucht dafür Zertifikate, die im Test nicht liegen. Der Feldbau selbst ist reine Datenstruktur.

Rein mechanisch, kein Verhaltenswechsel. Der Test danach ist der Wächter für alles Weitere.

**Files:**
- Modify: `src/main/java/com/example/stemplekarte/wallet/ApplePassService.java` (Feldblock aus `buildPass`, heute Zeilen 192-217)
- Test: `src/test/java/com/example/stemplekarte/wallet/ApplePassPunkteTest.java`

**Interfaces:**
- Produces: `PKGenericPass baueFelder(Card card, CustomerCard cc, boolean grid)` — paketsichtbar, damit der Test drankommt, ohne die Klasse nach aussen zu öffnen.

- [ ] **Step 1: Den Feldblock in eine eigene Methode ziehen**

In `ApplePassService.java` den gesamten Block von `var genericPass = PKGenericPass.builder()` bis zum Ende des `else`-Zweigs in eine neue Methode verschieben:

```java
    /**
     * Baut die sichtbaren Felder des Passes.
     *
     * Bewusst getrennt von buildPass: das signiert am Ende und braucht
     * Zertifikate, die im Test nicht liegen. Die Felder sind reine
     * Datenstruktur und dadurch pruefbar - und genau sie sind der Teil, bei
     * dem ein Fehler teuer wird. Ein unpassendes Feld hat iOS schon einmal
     * den aktualisierten Pass verwerfen lassen (damals groupingIdentifier):
     * Karte installiert, aktualisiert aber nie.
     *
     * Paketsichtbar, damit der Test drankommt, ohne die Klasse nach aussen
     * zu oeffnen.
     */
    PKGenericPass baueFelder(Card card, CustomerCard cc, boolean grid) {
        int threshold = card.getRewardThreshold();
        String reward = rewardText(cc.getStamps(), threshold, card.getRewardText());
        String stampRatio = Math.min(cc.getStamps(), threshold) + "/" + threshold;
        int remaining = Math.max(0, threshold - cc.getStamps());
        String countdownLabel = remaining > 0 ? "Stempel bis ↓" : "BELOHNUNG";
        String countdownValue = remaining > 0 ? String.valueOf(remaining) : "Bereit! 🎉";
        String changeMsg = (cc.getStamps() >= threshold)
                ? "🎉 " + card.getRewardText() + " verdient! Neue Karte: %@"
                : "Update! Dein Stempelstand: %@";

        var genericPass = PKGenericPass.builder().passType(PKPassType.PKStoreCard);

        // ... der bestehende if (grid) / else Block, unveraendert ...

        return genericPass.build();
    }
```

In `buildPass` bleibt dafür:

```java
        PKGenericPass genericPass = baueFelder(card, cc, grid);
```

Die Variablen `reward`, `stampRatio`, `remaining`, `countdownLabel`, `countdownValue`, `changeMsg` wandern dabei mit in die neue Methode und verschwinden aus `buildPass`. `threshold` wird in `buildPass` weiter für `sperrbildschirmOrte` gebraucht und bleibt dort stehen.

- [ ] **Step 2: Den Regressionstest schreiben**

`src/test/java/com/example/stemplekarte/wallet/ApplePassPunkteTest.java`:

```java
package com.example.stemplekarte.wallet;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.PointsRounding;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.service.RewardService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Der wichtigste Test dieses Umbaus.
 *
 * Der Pass nutzte bisher NUR Kopf-, Haupt-, Neben- und Zusatzfeld -
 * Rueckseitenfelder gab es keine. Der Praemien-Katalog wird das erste.
 * Rutscht dieses Feld versehentlich auch in den Stempel-Pass, aendert sich
 * der Pass fuer Karten, die gerade in echten Laeden liegen. Ein
 * unpassendes Feld hat iOS schon einmal den aktualisierten Pass verwerfen
 * lassen (damals groupingIdentifier): Karte installiert, aktualisiert aber
 * nie - und das faellt erst auf, wenn ein Kunde sich beschwert, dass sein
 * Stempel nicht ankommt.
 *
 * Deshalb steht hier beides: was der Punkte-Pass koennen MUSS und was der
 * Stempel-Pass weiterhin NICHT haben darf.
 */
class ApplePassPunkteTest {

    private ApplePassService service() {
        // Fuer den Feldbau reicht ein Dienst ohne Zertifikate: baueFelder
        // signiert nichts, und loadSigningInfo gibt null zurueck, wenn die
        // Zertifikatsdateien fehlen - genau der Fall im Test.
        //
        // ZWEISTELLIGER Konstruktor, wie er heute ist. Den RewardService
        // bekommt die Klasse erst in Task 2; bis dahin sind die beiden
        // Punkte-Tests unten mit @Disabled markiert.
        return new ApplePassService(mock(AppProperties.class),
                mock(PassTemplateGenerator.class));
    }

    private CustomerCard karteMit(Card card, int stamps, long pointsX100) {
        Customer customer = mock(Customer.class);
        when(customer.getName()).thenReturn("Adham");
        CustomerCard cc = mock(CustomerCard.class);
        when(cc.getCustomer()).thenReturn(customer);
        when(cc.getCard()).thenReturn(card);
        when(cc.getStamps()).thenReturn(stamps);
        when(cc.getPointsX100()).thenReturn(pointsX100);
        return cc;
    }

    @Test
    void stempelPass_hatWeiterhinKeineRueckseitenfelder() {
        Shop shop = mock(Shop.class);
        Card card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");

        var pass = service().baueFelder(card, karteMit(card, 3, 0), false);

        assertThat(pass.getBackFields()).isNullOrEmpty();
    }

    @Test
    void stempelPass_behaeltSeineFelder() {
        Shop shop = mock(Shop.class);
        Card card = Card.create(shop, "Kaffee", "10 Stempel", 10, "Gratis Kaffee");

        var pass = service().baueFelder(card, karteMit(card, 3, 0), false);

        assertThat(pass.getHeaderFields()).hasSize(1);
        assertThat(pass.getHeaderFields().get(0).getValue()).isEqualTo("3/10");
        assertThat(pass.getPrimaryFields()).hasSize(1);
        // Countdown: noch 7 Stempel
        assertThat(pass.getPrimaryFields().get(0).getValue()).isEqualTo("7");
    }

    @Test
    @org.junit.jupiter.api.Disabled("Konstruktor bekommt den RewardService erst in Task 2")
    void punktePass_zeigtStandUndZiel() {
        Shop shop = mock(Shop.class);
        Card card = Card.createPoints(shop, "Bistro", "Punkte", 100, PointsRounding.GENAU);

        var pass = service().baueFelder(card, karteMit(card, 0, 34_000), false);

        assertThat(pass.getHeaderFields().get(0).getLabel()).isEqualTo("PUNKTE");
        assertThat(pass.getHeaderFields().get(0).getValue()).isEqualTo("340");
    }

    @Test
    @org.junit.jupiter.api.Disabled("Konstruktor bekommt den RewardService erst in Task 2")
    void punktePass_traegtDenKatalogAufDerRueckseite() {
        Shop shop = mock(Shop.class);
        Card card = Card.createPoints(shop, "Bistro", "Punkte", 100, PointsRounding.GENAU);

        var pass = service().baueFelder(card, karteMit(card, 0, 34_000), false);

        // Der Katalog kommt aus dem RewardService-Mock; ohne Praemien hat
        // die Rueckseite nur die Kurs-Zeile.
        assertThat(pass.getBackFields()).isNotEmpty();
    }
}
```

> **Hinweis:** `ApplePassService` bekommt in Step 3 des nächsten Tasks den `RewardService` in den Konstruktor. Solange er das nicht hat, wird der Test hier mit dem heutigen Konstruktor geschrieben und die letzten zwei Tests mit `@Disabled("kommt in Task 2")` markiert. Nach Task 2 wird die Markierung entfernt.

- [ ] **Step 3: Tests laufen lassen**

Run: `./mvnw -o test -Dtest=ApplePassPunkteTest`
Expected: die ersten zwei Tests grün, die zwei markierten übersprungen

- [ ] **Step 4: Volle Suite**

Run: `./mvnw -o test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/stemplekarte/wallet/ApplePassService.java \
        src/test/java/com/example/stemplekarte/wallet/ApplePassPunkteTest.java
git commit -m "refactor: Feldbau des Apple-Passes testbar herausgezogen"
```

---

### Task 2: Punkte-Zweig im Apple-Pass

**Files:**
- Modify: `src/main/java/com/example/stemplekarte/wallet/ApplePassService.java`
- Test: `src/test/java/com/example/stemplekarte/wallet/ApplePassPunkteTest.java` (Markierungen entfernen)

**Interfaces:**
- Consumes: `RewardService.list(Card)`, `RewardService.naechstesZiel(List, long)`, `PointsMath.formatiere(long)`.

- [ ] **Step 1: `RewardService` in den Konstruktor**

```java
    private final RewardService rewardService;

    public ApplePassService(AppProperties props,
                            PassTemplateGenerator templateGenerator,
                            RewardService rewardService) {
        this.props = props;
        this.templateGenerator = templateGenerator;
        this.rewardService = rewardService;
    }
```

Alle bestehenden Aufrufstellen des Konstruktors anpassen (Spring macht das selbst; im Test steht er in `ApplePassPunkteTest`).

- [ ] **Step 2: Den Punkte-Zweig in `baueFelder` einbauen**

Ganz am Anfang der Methode, vor allem Stempel-Kram:

```java
    PKGenericPass baueFelder(Card card, CustomerCard cc, boolean grid) {
        if (card.isPoints()) {
            return baueFelderPunkte(card, cc);
        }
        // ... ab hier unveraendert der bisherige Stempel-Weg ...
    }
```

Der Stempel-Weg wird dadurch nicht angefasst — das ist der ganze Sinn der Trennung.

- [ ] **Step 3: `baueFelderPunkte` schreiben**

```java
    /**
     * Felder einer Punktekarte.
     *
     * Vorne Stand und Ziel, hinten der ganze Katalog: der Kunde soll auf
     * einen Blick sehen, was zu holen ist, und trotzdem ein konkretes
     * naechstes Ziel haben. Ohne das Ziel verschwindet der Zugreiz, der die
     * Stempelkarte traegt ("noch zwei, dann ist der Kuchen drin").
     *
     * Das changeMessage uebernimmt die Rolle von "Karte voll": es meldet
     * sich, wenn der Stand eine Praemie erreicht hat - nicht bei jeder
     * Buchung, sonst wird die Sperrbildschirm-Meldung zum Rauschen.
     */
    private PKGenericPass baueFelderPunkte(Card card, CustomerCard cc) {
        long stand = cc.getPointsX100();
        List<Reward> katalog = rewardService.list(card);
        Reward ziel = RewardService.naechstesZiel(katalog, stand);

        String standText = PointsMath.formatiere(stand);
        boolean etwasErreichbar = katalog.stream()
                .anyMatch(r -> r.getCostPointsX100() <= stand);

        // %@ ersetzt Apple durch den neuen Wert des Feldes.
        String changeMsg = etwasErreichbar
                ? "🎉 Du kannst einloesen! Punktestand: %@"
                : "Update! Dein Punktestand: %@";

        var genericPass = PKGenericPass.builder()
                .passType(PKPassType.PKStoreCard)
                .headerFieldBuilder(PKField.builder()
                        .key("points-header").label("PUNKTE")
                        .value(standText)
                        .changeMessage(changeMsg));

        if (ziel != null) {
            long fehlend = Math.max(0, ziel.getCostPointsX100() - stand);
            genericPass
                    .primaryFieldBuilder(PKField.builder()
                            .key("points-goal")
                            .label(fehlend > 0 ? "Punkte bis ↓" : "BEREIT")
                            .value(fehlend > 0
                                    ? PointsMath.formatiere(fehlend)
                                    : "Bereit! 🎉"))
                    .secondaryFieldBuilder(PKField.builder()
                            .key("reward").label("NÄCHSTE PRÄMIE")
                            .value(ziel.getName()));
        } else {
            // Leerer Katalog: nur der Stand, kein erfundenes Ziel. Ein Laden
            // ohne Praemien hat schlicht noch keines.
            genericPass.primaryFieldBuilder(PKField.builder()
                    .key("points-big").label("PUNKTE").value(standText));
        }

        genericPass.auxiliaryFieldBuilder(PKField.builder()
                .key("name").label("KUNDE").value(cc.getCustomer().getName()));

        // ── Rueckseite: der ganze Katalog ─────────────────────────────────
        // Das sind die ERSTEN Rueckseitenfelder ueberhaupt in diesem Pass.
        // Sie liegen bewusst nur in diesem Zweig - der Stempel-Pass bleibt
        // Feld fuer Feld wie er war.
        for (Reward r : katalog) {
            boolean bezahlbar = r.getCostPointsX100() <= stand;
            genericPass.backFieldBuilder(PKField.builder()
                    .key("reward-" + r.getId())
                    .label(bezahlbar ? "✓ " + r.getName() : r.getName())
                    .value(PointsMath.formatiere(r.getCostPointsX100()) + " Punkte"));
        }

        genericPass.backFieldBuilder(PKField.builder()
                .key("rate").label("So sammelst du")
                .value(kursText(card)));

        return genericPass.build();
    }

    /** Der Kurs im Klartext, damit der Kunde seine Punkte selbst nachrechnen
     *  kann. Unter einem Punkt pro Euro liest sich der Kehrwert besser. */
    private String kursText(Card card) {
        int kurs = card.getPointsPerEuroX100();
        if (kurs >= 100) {
            return PointsMath.formatiere(kurs) + " Punkte pro Euro";
        }
        long euroProPunktX100 = Math.round(10000.0 / kurs);
        return "1 Punkt pro " + PointsMath.formatiere(euroProPunktX100) + " Euro";
    }
```

Importe ergänzen: `com.example.stemplekarte.model.Reward`, `com.example.stemplekarte.service.PointsMath`, `com.example.stemplekarte.service.RewardService`.

- [ ] **Step 4: Die Markierungen im Test entfernen**

`@Disabled` aus den zwei Punkte-Tests nehmen. Im Test den `RewardService`-Mock füttern:

```java
        RewardService rewardService = mock(RewardService.class);
        when(rewardService.list(card)).thenReturn(List.of(
                Reward.create(card, "Kaffee", 10_000, 0),
                Reward.create(card, "Kuchen", 25_000, 1)));
```

Und einen Test ergänzen, der die Reihenfolge der Ziele festnagelt:

```java
    @Test
    void punktePass_zieltAufDieBilligsteNochNichtBezahlbare() {
        Shop shop = mock(Shop.class);
        Card card = Card.createPoints(shop, "Bistro", "Punkte", 100, PointsRounding.GENAU);
        RewardService rewards = mock(RewardService.class);
        when(rewards.list(card)).thenReturn(List.of(
                Reward.create(card, "Kaffee", 10_000, 0),
                Reward.create(card, "Kuchen", 25_000, 1)));

        var service = new ApplePassService(mock(AppProperties.class),
                mock(PassTemplateGenerator.class), rewards);

        // Stand 150 Punkte: Kaffee (100) bezahlt, Kuchen (250) offen.
        var pass = service.baueFelder(card, karteMit(card, 0, 15_000), false);

        assertThat(pass.getSecondaryFields().get(0).getValue()).isEqualTo("Kuchen");
        assertThat(pass.getPrimaryFields().get(0).getValue()).isEqualTo("100");
        // Rueckseite: zwei Praemien plus die Kurs-Zeile
        assertThat(pass.getBackFields()).hasSize(3);
        assertThat(pass.getBackFields().get(0).getLabel()).startsWith("✓");
    }
```

- [ ] **Step 5: Tests laufen lassen**

Run: `./mvnw -o test`
Expected: PASS. `stempelPass_hatWeiterhinKeineRueckseitenfelder` ist dabei der Beweis, dass der Live-Pfad unberührt ist.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/stemplekarte/wallet/ApplePassService.java \
        src/test/java/com/example/stemplekarte/wallet/ApplePassPunkteTest.java
git commit -m "feat: Apple-Pass zeigt Punkte, Ziel und Praemien-Katalog"
```

---

### Task 3: Sperrbildschirm für Punktekarten

**Files:**
- Modify: `src/main/java/com/example/stemplekarte/wallet/ApplePassService.java` (`sperrbildschirmOrte`, heute Zeilen 276-307, und der Aufruf in `buildPass`)
- Test: `src/test/java/com/example/stemplekarte/wallet/ApplePassPunkteTest.java`

**Interfaces:**
- Produces: `sperrbildschirmOrte(Shop shop, Card card, CustomerCard cc)` — Signatur wechselt, damit die Methode sich den Rest selbst holt, statt vier Einzelwerte durchgereicht zu bekommen.

- [ ] **Step 1: Den Test schreiben**

```java
    @Test
    void sperrbildschirm_fuelltDieselbenPlatzhalterMitPunkten() {
        // Der Laden schreibt seinen Text einmal und erwartet, dass er auf
        // beiden Kartentypen funktioniert. {stempel}/{stamps} traegt bei
        // Punktekarten die fehlende Punktzahl, {belohnung}/{reward} den
        // Namen der naechsten Praemie. Kein neuer Platzhalter, keine
        // Migration - bestehende Texte laufen weiter.
        Shop shop = mock(Shop.class);
        when(shop.isLockScreenActive()).thenReturn(true);
        when(shop.getLatitude()).thenReturn(52.5);
        when(shop.getLongitude()).thenReturn(13.4);
        when(shop.getLockScreenTextProgress())
                .thenReturn("Noch {stamps} Punkte bis: {reward}");

        Card card = Card.createPoints(shop, "Bistro", "Punkte", 100, PointsRounding.GENAU);
        RewardService rewards = mock(RewardService.class);
        when(rewards.list(card)).thenReturn(List.of(
                Reward.create(card, "Kuchen", 25_000, 0)));

        var service = new ApplePassService(mock(AppProperties.class),
                mock(PassTemplateGenerator.class), rewards);

        var orte = service.sperrbildschirmOrte(shop, card, karteMit(card, 0, 9_000));

        assertThat(orte).hasSize(1);
        assertThat(orte.get(0).getRelevantText()).isEqualTo("Noch 160 Punkte bis: Kuchen");
    }

    @Test
    void sperrbildschirm_ohneKatalogBleibtLeer() {
        // Kein Ziel, kein sinnvoller Text. Lieber nichts anzeigen als
        // "Noch 0 Punkte bis: null".
        Shop shop = mock(Shop.class);
        when(shop.isLockScreenActive()).thenReturn(true);
        Card card = Card.createPoints(shop, "Bistro", "Punkte", 100, PointsRounding.GENAU);
        RewardService rewards = mock(RewardService.class);
        when(rewards.list(card)).thenReturn(List.of());

        var service = new ApplePassService(mock(AppProperties.class),
                mock(PassTemplateGenerator.class), rewards);

        assertThat(service.sperrbildschirmOrte(shop, card, karteMit(card, 0, 9_000)))
                .isEmpty();
    }
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `./mvnw -o test -Dtest=ApplePassPunkteTest`
Expected: FAIL, Signatur passt nicht

- [ ] **Step 3: `sperrbildschirmOrte` umbauen**

```java
    /**
     * Ortsbindung fuer den Sperrbildschirm. Leere Liste heisst: keine
     * Ortsbindung, iOS zeigt nichts an.
     *
     * Die Platzhalter bleiben dieselben wie bisher. Bei einer Punktekarte
     * traegt {stempel}/{stamps} die fehlende Punktzahl und
     * {belohnung}/{reward} den Namen der naechsten Praemie - so laeuft ein
     * Text, den ein Laden fuer seine Stempelkarte geschrieben hat,
     * unveraendert auch auf einer Punktekarte.
     *
     * Paketsichtbar fuer den Test.
     */
    List<PKLocation> sperrbildschirmOrte(Shop shop, Card card, CustomerCard cc) {
        if (!shop.isLockScreenActive()) {
            return List.of();
        }

        String belohnung;
        long fehlendAnzeige;
        boolean erreicht;

        if (card.isPoints()) {
            var katalog = rewardService.list(card);
            Reward ziel = RewardService.naechstesZiel(katalog, cc.getPointsX100());
            // Ohne Praemie gibt es nichts Sinnvolles zu melden. Lieber
            // keine Ortsbindung als "Noch 0 Punkte bis: null".
            if (ziel == null) return List.of();
            belohnung = ziel.getName();
            long fehlend = Math.max(0, ziel.getCostPointsX100() - cc.getPointsX100());
            fehlendAnzeige = fehlend;
            erreicht = fehlend == 0;
        } else {
            belohnung = card.getRewardText();
            int fehlend = card.getRewardThreshold() - cc.getStamps();
            fehlendAnzeige = Math.max(0, fehlend) * 100L;  // fuer die gemeinsame Ausgabe
            erreicht = fehlend <= 0;
        }

        String offen = card.isPoints()
                ? PointsMath.formatiere(fehlendAnzeige)
                : String.valueOf(fehlendAnzeige / 100);
        String einheit = card.isPoints() ? "Punkte" : "Stempel";

        String eigener = erreicht
                ? shop.getLockScreenTextFull()
                : shop.getLockScreenTextProgress();

        String hinweis;
        if (eigener != null && !eigener.isBlank()) {
            hinweis = eigener
                    .replace("{belohnung}", belohnung)
                    .replace("{reward}", belohnung)
                    .replace("{stempel}", offen)
                    .replace("{stamps}", offen);
        } else {
            hinweis = erreicht
                    ? belohnung + " wartet auf dich"
                    : "Noch " + offen + " " + einheit + " bis: " + belohnung;
        }

        return List.of(PKLocation.builder()
                .latitude(shop.getLatitude())
                .longitude(shop.getLongitude())
                .relevantText(hinweis)
                .build());
    }
```

Die Standard-Formulierung bei einem Stempel lautet heute „Noch 1 Stempel bis: …" statt „Noch 1 Stempel**e**". Damit sich für Stempelkarten nichts ändert, den Einzahl-Fall beibehalten:

```java
            String einheitGebeugt = (!card.isPoints() && fehlendAnzeige == 100)
                    ? "Stempel" : einheit;
```

und in der Standard-Formulierung verwenden. Der bestehende Zweig `fehlend == 1 ? "Noch 1 Stempel bis: "` ist damit abgedeckt.

- [ ] **Step 4: Den Aufruf in `buildPass` anpassen**

```java
        List<PKLocation> orte = sperrbildschirmOrte(shop, card, cc);
```

- [ ] **Step 5: Tests laufen lassen**

Run: `./mvnw -o test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/stemplekarte/wallet/ApplePassService.java \
        src/test/java/com/example/stemplekarte/wallet/ApplePassPunkteTest.java
git commit -m "feat: Sperrbildschirm-Text auch fuer Punktekarten"
```

---

### Task 4: Google Wallet

**Files:**
- Modify: `src/main/java/com/example/stemplekarte/wallet/GoogleWalletService.java` (`createOrUpdateObject`, heute Zeilen 239-292)

**Interfaces:**
- Consumes: `RewardService`, `PointsMath`.

- [ ] **Step 1: `RewardService` in den Konstruktor aufnehmen**

```java
    private final RewardService rewardService;
```

im Konstruktor setzen, wie in `ApplePassService`.

- [ ] **Step 2: Den Punkte-Zweig einbauen**

In `createOrUpdateObject` die beiden `LoyaltyPoints`-Blöcke nach Kartentyp trennen:

```java
        Card card = cc.getCard();

        LoyaltyPoints haupt;
        LoyaltyPoints neben;
        List<TextModuleData> module = new ArrayList<>();

        if (card.isPoints()) {
            long stand = cc.getPointsX100();
            List<Reward> katalog = rewardService.list(card);
            Reward ziel = RewardService.naechstesZiel(katalog, stand);

            haupt = new LoyaltyPoints()
                    .setLabel("Punkte")
                    .setBalance(new LoyaltyPointsBalance()
                            .setString(PointsMath.formatiere(stand)));

            if (ziel != null) {
                long fehlend = Math.max(0, ziel.getCostPointsX100() - stand);
                neben = new LoyaltyPoints()
                        .setLabel("Nächste Prämie")
                        .setBalance(new LoyaltyPointsBalance()
                                .setString(fehlend > 0
                                        ? ziel.getName() + ", noch "
                                            + PointsMath.formatiere(fehlend)
                                        : ziel.getName() + " bereit!"));
            } else {
                neben = new LoyaltyPoints()
                        .setLabel("Prämien")
                        .setBalance(new LoyaltyPointsBalance().setString("noch keine"));
            }

            // Der Katalog als Textbaustein. Google zeigt ihn beim Aufklappen
            // der Karte - das Gegenstueck zur Pass-Rueckseite bei Apple.
            if (!katalog.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Reward r : katalog) {
                    boolean bezahlbar = r.getCostPointsX100() <= stand;
                    sb.append(bezahlbar ? "✓ " : "· ")
                      .append(r.getName()).append(" - ")
                      .append(PointsMath.formatiere(r.getCostPointsX100()))
                      .append(" Punkte\n");
                }
                module.add(new TextModuleData()
                        .setId("katalog")
                        .setHeader("Prämien")
                        .setBody(sb.toString().trim()));
            }
        } else {
            // Unveraendert der bisherige Stempel-Weg.
            haupt = new LoyaltyPoints()
                    .setLabel("Stempel")
                    .setBalance(new LoyaltyPointsBalance()
                            // Gedeckelt: gesenkte Schwelle wuerde sonst "10/5" anzeigen.
                            .setString(Math.min(cc.getStamps(), card.getRewardThreshold())
                                    + "/" + card.getRewardThreshold()));
            neben = new LoyaltyPoints()
                    .setLabel("Belohnung")
                    .setBalance(new LoyaltyPointsBalance()
                            .setString(card.getRewardText()));
        }

        LoyaltyObject loyaltyObject = new LoyaltyObject()
                .setId(objectId)
                .setClassId(classId)
                .setState("ACTIVE")
                .setAccountName(cc.getCustomer().getName())
                .setAccountId(cc.getId())
                .setLoyaltyPoints(haupt)
                .setSecondaryLoyaltyPoints(neben)
                .setTextModulesData(module)
                .setBarcode(new Barcode()
                        .setType("QR_CODE")
                        .setValue(qrValue)
                        .setAlternateText(""));
```

`setTextModulesData(module)` mit leerer Liste bei Stempelkarten: das überschreibt einen alten Baustein sauber mit nichts und lässt Stempelkarten aussehen wie bisher.

Importe ergänzen: `com.google.api.services.walletobjects.model.TextModuleData`, `java.util.ArrayList`, `com.example.stemplekarte.model.Reward`, `com.example.stemplekarte.model.Card`, `com.example.stemplekarte.service.PointsMath`, `com.example.stemplekarte.service.RewardService`.

- [ ] **Step 3: `notifyCardFull` für Punkte**

Die Methode bekommt den Text schon von aussen (`WalletNotifier.googleKarteVoll`), sie bleibt unverändert. Nur prüfen, dass der Aufruf in `PointsController.melden` einen sinnvollen Text schickt — dort steht „Du kannst jetzt einloesen". Bei einem Ziel im Ergebnis besser den Namen mitgeben:

```java
        if (e.neuesZielErreicht()) {
            notifier.googleKarteVoll(e.customerCardId(), "Praemie verfuegbar");
        }
```

Das steht nach den Korrekturen aus Teil 1 bereits so da — hier nur zur
Kontrolle beim Durchgehen.

- [ ] **Step 4: Bauen und Tests**

Run: `./mvnw -o test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/stemplekarte/wallet/GoogleWalletService.java \
        src/main/java/com/example/stemplekarte/controller/PointsController.java
git commit -m "feat: Google Wallet zeigt Punkte und den Praemien-Katalog"
```

---

### Task 5: Kundenseite

**Files:**
- Modify: `src/main/java/com/example/stemplekarte/controller/LandingController.java` (Kartenseite, heute ab Zeile 67)

**Interfaces:**
- Consumes: `RewardService`, `PointsMath`, das SSE-Ereignis `points` aus `CardEventHub.publishPoints` (Teil 1, Task 6).

- [ ] **Step 1: Den Punkte-Zweig anlegen**

In der Methode hinter `@GetMapping("/karte/{customerId}/{cardId}")` nach dem Laden der `CustomerCard` verzweigen:

```java
            if (cc.getCard().isPoints()) {
                return punkteSeite(cc, customerId, cardId);
            }
```

Der Stempel-Weg darunter bleibt Zeile für Zeile wie er ist.

- [ ] **Step 2: `punkteSeite` schreiben**

Statt des Stempelrasters (`<div class='stamp filled'>☕</div>`) baut die Seite eine Katalogliste mit Fortschrittsbalken:

```java
    /**
     * Kartenseite einer Punktekarte.
     *
     * Zeigt Stand, naechstes Ziel und darunter den ganzen Katalog - dieselbe
     * Aufteilung wie auf der Wallet-Karte, damit der Kunde nicht zwei
     * verschiedene Darstellungen derselben Sache lernen muss.
     *
     * Deutsch, wie die Stempelseite: das Backend hat noch keine
     * Uebersetzungstabelle (siehe 2026-08-30-arabisch-rtl-sprache-design.md).
     */
    private String punkteSeite(CustomerCard cc, String customerId, String cardId) {
        Card card = cc.getCard();
        long stand = cc.getPointsX100();
        List<Reward> katalog = rewardService.list(card);
        Reward ziel = RewardService.naechstesZiel(katalog, stand);

        StringBuilder katalogHtml = new StringBuilder();
        for (Reward r : katalog) {
            boolean bezahlbar = r.getCostPointsX100() <= stand;
            int prozent = r.getCostPointsX100() > 0
                    ? (int) Math.min(100, stand * 100 / r.getCostPointsX100())
                    : 100;
            katalogHtml.append("""
                    <div class="reward %s">
                      <div class="reward-head">
                        <span class="reward-name">%s</span>
                        <span class="reward-cost">%s Punkte</span>
                      </div>
                      <div class="bar"><div class="bar-fill" style="width:%d%%"></div></div>
                    </div>
                    """.formatted(
                    bezahlbar ? "ready" : "",
                    escape(r.getName()),
                    PointsMath.formatiere(r.getCostPointsX100()),
                    prozent));
        }

        String zielZeile = ziel == null
                ? "Noch keine Prämie hinterlegt"
                : (ziel.getCostPointsX100() <= stand
                    ? ziel.getName() + " ist bereit!"
                    : "Noch " + PointsMath.formatiere(ziel.getCostPointsX100() - stand)
                        + " Punkte bis: " + ziel.getName());

        // ... in dieselbe HTML-Vorlage einsetzen wie die Stempelseite,
        //     nur mit .points-Block statt .stamps-grid ...
    }
```

Der Bestand an Stilen wird übernommen; neu sind `.reward`, `.reward-head`, `.bar`, `.bar-fill` und `.ready`.

- [ ] **Step 3: Das SSE-Ereignis `points` im Seiten-JavaScript**

Neben dem bestehenden `es.addEventListener('stamps', ...)`:

```js
                                es.addEventListener('points', function (ev) {
                                    try {
                                        var d = JSON.parse(ev.data);
                                        if (typeof d.pointsX100 === 'number') {
                                            renderPoints(d.pointsX100, d.zielName, d.fehlendX100);
                                        }
                                    } catch (e) {}
                                });
```

`renderPoints` tauscht Stand, Zielzeile und die Balkenbreiten im DOM aus. **Kein `location.reload()`** — genau das war der Hotfix vom 28.08.: iOS Safari lieferte manchmal die alte Seite aus dem Cache, und der Stempel kam beim Kunden nicht an.

Die bestehenden Vorsichtsmassnahmen gelten unverändert mit: `fetch(..., {cache:'no-store'})`, `Cache-Control: no-store` auf HTML und JSON, Abgleich bei `visibilitychange` / `pageshow` / `online`.

- [ ] **Step 4: Von Hand prüfen**

Run: `./mvnw -o spring-boot:run`

Kartenseite einer Punktekarte im Browser öffnen, parallel im Scanner buchen. Der Stand muss sich ohne Neuladen ändern, und der Balken der nächsten Prämie muss wachsen.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/stemplekarte/controller/LandingController.java
git commit -m "feat: Kundenseite zeigt Punktestand und Praemien-Katalog"
```

---

### Task 6: Statistik

**Files:**
- Modify: `src/main/java/com/example/stemplekarte/service/StatsService.java` (`summary`, heute Zeilen 52-135)
- Test: `src/test/java/com/example/stemplekarte/service/PunkteStatistikTest.java`

**Interfaces:**
- Consumes: `PointsBookingRepository` (Teil 1, Task 5).
- Produces: vier neue Schlüssel in der Antwort von `summary`: `pointsRevenueCents`, `pointsGrantedX100`, `pointsOutstandingX100`, `pointsRedemptions`, plus `topRewards` als Rangliste.

- [ ] **Step 1: Einen Fehler im bestehenden Schleifenkörper beheben**

`summary` rechnet heute für **jede** Karte:

```java
            stampsGranted += (long) stamps + (long) rewards * threshold;
```

Bei einer Punktekarte ist `stamps` null und `threshold` eins — jede eingelöste Prämie erhöht damit `stampsGranted` um eins und verfälscht „vergebene Stempel". Also die Stempel-Summen nur noch für Stempelkarten bilden:

```java
        for (Card card : alleKarten) {
            List<CustomerCard> ccs = customerCardRepo.findByCard(card);

            if (card.isPoints()) {
                punkteAusstehendGesamt += punkteKarte(card, ccs, perCard);
                continue;   // Stempel-Kennzahlen gelten fuer diese Karte nicht
            }

            // ... unveraendert der bisherige Stempel-Block ...
        }
```

Neukunden je Tag und `newThisWeek` / `newLastWeek` gelten für beide Typen — diesen Teil aus dem Stempel-Block in eine eigene Schleife über `ccs` ziehen, die vor dem `continue` läuft.

- [ ] **Step 2: Den Test schreiben**

`src/test/java/com/example/stemplekarte/service/PunkteStatistikTest.java`:

```java
package com.example.stemplekarte.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Zahl, die es heute nicht gibt und die dem Laden am meisten sagt: der
 * ausstehende Punktebestand. Das ist die Verpflichtung, die er sich
 * angesammelt hat - was seine Kunden noch einloesen duerfen.
 *
 * Ausserdem festgenagelt: Punktekarten duerfen die Stempel-Kennzahlen NICHT
 * verfaelschen. Vorher lief jede Karte durch dieselbe Schleife, und weil
 * eine Punktekarte reward_threshold = 1 traegt, erhoehte dort jede
 * eingeloeste Praemie die "vergebenen Stempel" um eins.
 */
class PunkteStatistikTest {

    @Test
    void ausstehenderBestandIstDieSummeDerStaende() {
        // 340 + 50 + 0 Punkte auf drei Karten
        assertThat(StatsService.ausstehendePunkte(
                java.util.List.of(34_000L, 5_000L, 0L)))
                .isEqualTo(39_000L);
    }

    @Test
    void ohneKartenIstDerBestandNull() {
        assertThat(StatsService.ausstehendePunkte(java.util.List.of())).isZero();
    }
}
```

Ergänzend in `StatsService` die reine Hilfsfunktion, damit genau diese Regel prüfbar ist:

```java
    /** Was die Kunden noch einloesen duerfen. Bewusst statisch und ohne
     *  Datenbank, damit die Regel in einem Einheitentest steht. */
    static long ausstehendePunkte(List<Long> staende) {
        return staende.stream().mapToLong(Long::longValue).sum();
    }
```

- [ ] **Step 3: `punkteKarte` schreiben**

```java
    /**
     * Kennzahlen einer Punktekarte. Bewusst getrennt von den Stempel-Summen:
     * Stempel und Punkte sind verschiedene Einheiten und gehoeren nicht in
     * dieselbe Saeule.
     */
    private long punkteKarte(Card card, List<CustomerCard> ccs,
                             List<Map<String, Object>> perCard) {
        long ausstehend = ausstehendePunkte(
                ccs.stream().map(CustomerCard::getPointsX100).toList());
        int einloesungen = ccs.stream().mapToInt(CustomerCard::getTotalRewards).sum();

        if (card.isActive()) {
            Map<String, Object> m = new HashMap<>();
            m.put("cardId", card.getId());
            m.put("cardName", card.getName());
            m.put("type", "POINTS");
            m.put("customerCount", ccs.size());
            m.put("pointsOutstandingX100", ausstehend);
            m.put("totalRewards", einloesungen);
            perCard.add(m);
        }
        // Rueckgabe, damit die Schleife den Gesamtbestand aufsummieren kann.
        // Auch von deaktivierten Karten: eingeloest werden duerfen die Punkte
        // trotzdem noch, die Verpflichtung verschwindet nicht mit dem
        // Ausblenden der Karte.
        return ausstehend;
    }
```

Bei den Stempelkarten in `perCard` entsprechend `m.put("type", "STAMP")` ergänzen, damit die Oberfläche die beiden Listen auseinanderhalten kann.

- [ ] **Step 4: Umsatz, vergebene Punkte und Rangliste aus den Buchungen**

```java
        // 30-Tage-Fenster wie beim ScanLog-Sample, damit beide Auswertungen
        // denselben Zeitraum meinen.
        List<PointsBooking> buchungen = pointsBookingRepo
                .findByShopIdAndCreatedAtAfterOrderByCreatedAtAsc(shop.getId(), sampleWindowAgo);

        long umsatzCents = buchungen.stream()
                .filter(b -> b.getKind() == BookingKind.EARN && b.getAmountCents() != null)
                .mapToLong(PointsBooking::getAmountCents).sum();

        long punkteVergeben = buchungen.stream()
                .filter(b -> b.getKind() == BookingKind.EARN)
                .mapToLong(PointsBooking::getDeltaPointsX100).sum();

        // Nur nicht zurueckgenommene Einloesungen: eine Praemie, die
        // rueckgaengig gemacht wurde, hat der Kunde nie bekommen und gehoert
        // nicht in die Rangliste.
        Map<String, Long> topRewards = buchungen.stream()
                .filter(b -> b.getKind() == BookingKind.REDEEM && !b.istZurueckgenommen())
                .filter(b -> b.getRewardName() != null)
                .collect(Collectors.groupingBy(PointsBooking::getRewardName,
                        Collectors.counting()));
```

In die Ergebnis-Map aufnehmen. Sie heisst in `summary` schlicht `summary`
(angelegt bei Zeile 201, zurückgegeben bei 236) — also direkt davor:

```java
        summary.put("pointsRevenueCents", umsatzCents);
        summary.put("pointsGrantedX100", punkteVergeben);
        summary.put("pointsOutstandingX100", punkteAusstehendGesamt);
        summary.put("topRewards", topRewards);
```

`punkteAusstehendGesamt` wird in der Kartenschleife mitsummiert: in
`punkteKarte` den Rückgabewert von `ausstehendePunkte` aufaddieren.

`PointsBookingRepository` in den Konstruktor von `StatsService` aufnehmen.

- [ ] **Step 5: Tests laufen lassen**

Run: `./mvnw -o test`
Expected: PASS

- [ ] **Step 6: Anzeige im Frontend**

Im Frontend-Repo `src/components/StatsView.jsx`: bei Karten mit `type === 'POINTS'` die vier Zahlen zeigen statt der Stempel-Kacheln. Texte in `de`, `en`, `ar` ergänzen (Muster: Task 2 aus Teil 2). Der ausstehende Bestand bekommt einen erklärenden Untertitel — „was deine Kunden noch einlösen dürfen" —, sonst liest ihn niemand als das, was er ist.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/stemplekarte/service/StatsService.java \
        src/test/java/com/example/stemplekarte/service/PunkteStatistikTest.java
git commit -m "feat: Statistik rechnet Punkte, Umsatz und offenen Bestand"
```

---

## Abschluss

Nach Task 6 ist die Punktekarte vollständig: anlegen, buchen, einlösen, korrigieren, zurücknehmen, auf beiden Wallets und auf der Kundenseite sichtbar, in der Statistik ausgewertet.

**Vor dem Push:**

```bash
./mvnw -o test
```

**Nach dem Deploy prüfen**, in dieser Reihenfolge:

1. Eine **bestehende Stempelkarte** auf einem echten iPhone stempeln. Kommt der neue Stand an, ist der Pass unbeschädigt. Das zuerst, weil hier der Schaden am grössten wäre.
2. Eine Punktekarte anlegen, zwei Prämien, auf ein Handy laden.
3. Buchen und prüfen: Stand vorne, Katalog auf der Rückseite, Sperrbildschirm-Text am Laden.
4. Einlösen, dann zurücknehmen. Der Stand muss zweimal korrekt springen.
5. Statistik öffnen: Umsatz und ausstehender Bestand plausibel.

**Danach in der Vault festhalten:** `Projects/Stampit.md` auf Stand bringen — die Note ist seit dem 09.09. nicht aktualisiert und kennt die Punktekarte noch nicht. Ebenso `Knowledge/Stampit — Wettbewerbsanalyse bonice.md`: die Zeile „Punktekarte: nur Stempel-Zaehler, Aufwand gross" ist damit erledigt.
