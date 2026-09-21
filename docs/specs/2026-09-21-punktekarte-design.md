# Punktekarte (Punkte statt Stempel) — Design

**Datum:** 2026-09-21
**Projekt:** Stampit (v1). Betrifft `C:\Project SK\Stemplekarte` (Backend) +
`C:\Project SK\stempelkarte-frontend` (Laden-App).
**Anlass:** Stampit kann heute nur Stempel zählen. Läden mit wechselnden
Bonsummen (Bistro, Laden, Friseur) wollen nach Umsatz belohnen: wer für 145
Euro einkauft, bekommt entsprechend mehr als wer einen Kaffee holt. Der
direkte Wettbewerber bonice bietet beides an, Stampit bisher nur Stempel.

## 1. Ziel

Ein Laden legt eine **Punktekarte** an statt einer Stempelkarte. Das Personal
tippt beim Kassiervorgang den Rechnungsbetrag ein, das System rechnet ihn mit
dem Kurs des Ladens in Punkte um. Der Kunde sammelt und löst sie gegen eine
Prämie aus einem **Katalog** ein, den der Laden pflegt.

Der Kunde bekommt dafür nichts Neues in die Hand: er behält genau eine
Wallet-Karte mit genau einem QR-Code, wie heute.

### Erfolgskriterien

1. `Card.type` (`STAMP` | `POINTS`), beim Anlegen gewählt, danach fest.
   Bestehende Karten sind automatisch `STAMP` und verhalten sich unverändert.
2. Kurs pro Karte frei einstellbar, in beiden Sprechweisen: „X Punkte pro
   Euro" und „1 Punkt pro X Euro". Nachkommastellen bei den Punkten möglich
   (5,20 Euro bei 1 Euro = 1 Punkt ergibt 5,2 Punkte).
3. Rundungsregel wählt der Laden, nicht der Entwickler.
4. Prämien-Katalog pro Karte, jederzeit änderbar.
5. Jede Punktebewegung ist eine Buchung: nachlesbar, korrigierbar,
   zurücknehmbar.
6. Wallet-Karte zeigt Punktestand **und** Ziele: vorne die nächste erreichbare
   Prämie, hinten den ganzen Katalog.
7. `ddl-auto: update` läuft ohne DB-Reset durch (neue Spalten nullable oder
   mit Default, neue Tabellen rein additiv).
8. Bestehende Stempelkarten: keine sichtbare Änderung, Pass Feld für Feld
   identisch.

## 2. Getroffene Entscheidungen

| Entscheidung | Gewählt | Begründung |
|---|---|---|
| Einlösen | **Prämien-Katalog** mit mehreren Prämien zu verschiedenen Punktpreisen | Wunsch. Entspricht dem Marktstandard (BonusQR: „kleine Prämie 10 Punkte, große 50"). Eine feste Schwelle wäre nur die Stempelkarte mit anderem Zähler. |
| Wer löst ein | **Personal im Scanner** | Der Kunde soll weiterhin nur seine Wallet-Karte haben. Ein Kunden-Einlösecode wäre ein zweiter QR-Typ, eine neue Entity mit Ablaufzeit und ein Schritt mehr an der Theke. Fehlbuchungen sind stattdessen rücknehmbar. |
| Stempel und Punkte auf einer Karte | **Nein, ein Typ pro Karte** | Der Apple-Pass hat ein Hauptfeld und zwei Nebenfelder; zwei Zähler plus zwei Fortschritte drängeln sich dort. Wer beides will, legt zwei Karten an. Ein Zähler pro Karte hält Pass, Scanner und Statistik so einfach wie heute. |
| Kurs-Speicherung | **Ein Ganzzahl-Feld `pointsPerEuroX100`** (Punkte pro Euro, mal 100) | Deckt alle Sprechweisen ab: 100 = 1 Punkt pro Euro, 500 = 5 Punkte pro Euro, 20 = 1 Punkt pro 5 Euro, 150 = 3 Punkte pro 2 Euro. Ein Feld statt zwei, keine `double`-Rundungsfehler. |
| Punktestand-Speicherung | **`pointsX100` als `bigint`** (Hundertstel-Punkte) | Nachkommastellen ohne `double`. Gleiche Technik wie Cent bei Geld. Ein `double` zeigt irgendwann 5,199999 auf der Wallet-Karte. |
| Rundung | **Pro Karte einstellbar:** `GENAU` (2 Nachkommastellen), `ABRUNDEN`, `KAUFMAENNISCH` | Ausdrücklicher Wunsch: das entscheidet der Laden. `GENAU` ist der Normalfall, `ABRUNDEN` für Läden die runde Zahlen wollen. |
| Buchungstabelle | **Eigene Tabelle `PointsBooking`**, nicht `ScanLog` erweitern | `ScanLog` ist bewusst wegwerfbar: sein Schreibfehler wird geschluckt, damit ein Scan nie an der Statistik scheitert. Eine Buchung darf das nicht — scheitert sie, muss der Punktestand mit zurückrollen. Zwei Tabellen, zwei klare Aufgaben, und der Stempel-Pfad wird nicht angefasst. |
| Korrektur | **Alle drei Wege**, weil sie eine Mechanik sind | Handkorrektur mit Vorzeichen, Zurücknehmen der letzten Buchung und der bestehende Reset-Knopf fallen alle aus derselben Buchungszeile. Kein zweiter Mechanismus. |
| Wallet-Fortschritt | **Nächste erreichbare Prämie**, automatisch | Erhält den Zugreiz der Stempelkarte („noch zwei, dann ist der Kuchen drin"), ohne ein Feld, das der Laden pflegen muss. Der Katalog liefert die Zahl. |

**„Nächste Prämie" ist genau definiert:** die billigste aktive Prämie, die der
Kunde sich **noch nicht** leisten kann. Kann er sich alle leisten, steht dort
die teuerste mit dem Hinweis, dass alles erreichbar ist. Ist der Katalog leer,
zeigt der Pass nur den Punktestand und kein Ziel — ein Laden ohne Prämien hat
schlicht noch keines.

### Bewusst nicht im Umfang

- **Kein Punkteverfall.** Kein Ablaufdatum, keine Verfalls-Mail.
- **Keine Teil-Einlösung / Punkte als Rabattwert.** Punkte kaufen genau eine
  Prämie aus dem Katalog, keinen Euro-Betrag.
- **Keine Kundenstufen** (Bronze/Silber/Gold).
- **Kein Punktetransfer** zwischen Kunden.
- **Kein eigener Ziffernblock** im Scanner. Erst ein normales Feld mit
  `inputMode="decimal"`; ein Ziffernblock ist eine abgegrenzte Nachbesserung,
  wenn es an der Theke hakt.
- **Keine Buchungshistorie auf der Kundenseite.** Die Buchungen liegen vor,
  die Anzeige kann später ohne Umbau dazukommen.
- **Kein Doppelklick-Schutz per Anfrage-Kennung.** Der Stempel-Scan hat ihn
  auch nicht, der Knopf sperrt während des Ladens, und eine doppelte Buchung
  ist jetzt mit einem Griff zurückzunehmen.

## 3. Datenmodell

### `Card` — drei neue Spalten

| Spalte | Typ | Bedeutung |
|---|---|---|
| `type` | `varchar(16)`, Default `STAMP` | `STAMP` oder `POINTS`. Bestehende Karten werden damit automatisch Stempelkarten. |
| `points_per_euro_x100` | `int`, nullable | Kurs. Nur bei `POINTS` gesetzt. |
| `points_rounding` | `varchar(16)`, nullable | `GENAU`, `ABRUNDEN`, `KAUFMAENNISCH`. Nur bei `POINTS` gesetzt. |

`reward_threshold` und `reward_text` sind heute `NOT NULL` und bleiben es.
Eine bestehende Spalte nachträglich nullable zu machen schafft `ddl-auto`
nicht zuverlässig. Bei Punktekarten werden sie auf `1` und `""` gesetzt und
nirgends angezeigt.

### `Reward` — neu

Eine Prämie im Katalog einer Karte.

`id`, `card_id` (FK), `name`, `cost_points_x100` (`bigint`), `sort_order`,
`active` (`boolean`), `created_at`.

Gelöscht wird nie hart, nur `active = false`. Sonst verlieren alte Buchungen
ihren Bezug.

### `CustomerCard` — eine neue Spalte

`points_x100` (`bigint`, `not null default 0`).

`stamps` bleibt unberührt und gilt weiter nur für Stempelkarten.
`total_rewards` zählt auch eingelöste Prämien.

### `PointsBooking` — neu

Die Buchungszeile. Fundament für Korrektur, Rücknahme, Statistik und für die
Frage „was ist an dem Dienstag eigentlich passiert".

| Feld | Bedeutung |
|---|---|
| `id`, `created_at` | |
| `customer_card_id`, `card_id`, `shop_id`, `customer_id` | Nur IDs, keine harten Beziehungen — wie `ScanLog`, damit das Löschen einer Karte nicht an Buchungen scheitert. |
| `kind` | `EARN`, `REDEEM`, `CORRECTION`, `REVERSAL` |
| `delta_points_x100` | Mit Vorzeichen. Die einzige Wahrheit über die Bewegung. |
| `amount_cents` | Nur bei `EARN` und betragsbasierter `CORRECTION`, sonst null. |
| `points_per_euro_x100` | **Abschrift** des Kurses, der galt. |
| `reward_id`, `reward_name`, `reward_cost_points_x100` | Nur bei `REDEEM`. Name und Preis als **Abschrift**. |
| `staff_token_id` | Wer gebucht hat. Nullable (Buchung durch den Besitzer). |
| `reversal_of_id`, `reversed_at` | Zeigt auf die zurückgenommene Buchung, bzw. markiert die zurückgenommene. |

Indizes auf `(customer_card_id, created_at)` und `(shop_id, created_at)`.

Die beiden Abschriften sind der Punkt: ändert der Laden morgen den Kurs oder
benennt „Kuchen" in „Gebäck" um, erzählt die Historie trotzdem, was damals
passiert ist.

## 4. Rechenweg

```
pointsX100 = amountCents * pointsPerEuroX100 / 100
```

Alles `long`. Kein `double`, kein `BigDecimal`.

Gegenprobe mit 5,20 Euro Einkauf:

| Kurs | `pointsPerEuroX100` | Rechnung | Ergebnis |
|---|---|---|---|
| 1 Euro = 1 Punkt | 100 | 520 × 100 / 100 | 5,20 Punkte |
| 1 Euro = 5 Punkte | 500 | 520 × 500 / 100 | 26,00 Punkte |
| 5 Euro = 1 Punkt | 20 | 520 × 20 / 100 | 1,04 Punkte |
| 2 Euro = 3 Punkte | 150 | 520 × 150 / 100 | 7,80 Punkte |

Die Rundungsregel greift erst auf das Ergebnis:

- `GENAU`: auf das nächste Hundertstel Punkt.
- `ABRUNDEN`: auf ganze Punkte ab.
- `KAUFMAENNISCH`: auf ganze Punkte, ab der Hälfte auf.

Anzeige überall mit bis zu zwei Nachkommastellen, nachlaufende Nullen
abgeschnitten: `5,2` statt `5,20`, `26` statt `26,00`.

## 5. Endpunkte

Der Scanner kennt nach dem Scan den Kartentyp noch nicht — der steckt nicht im
QR, sondern hinter der `cardId`. Also fragt er zuerst.

| Route | Rumpf | Zweck |
|---|---|---|
| `POST /api/scan/state` | `{qrPayload}` | Kartentyp, Name, Stand, Katalog, letzte Buchung. Steuert, welche Oberfläche der Scanner zeigt. |
| `POST /api/points/earn` | `{qrPayload, amountCents}` | Einkauf buchen. |
| `POST /api/points/redeem` | `{qrPayload, rewardId}` | Prämie abbuchen. |
| `POST /api/points/correct` | genau eins von `amountCents` **oder** `pointsX100`, mit Vorzeichen | Handkorrektur. Beide gesetzt oder beide leer: 400. |
| `POST /api/points/undo` | `{qrPayload, bookingId}` | Gegenbuchung zur genannten Buchung. |

**Grenzen**, überall geprüft und damit auch gegen Überlauf:

- `amountCents` bei `earn`: 1 bis 9 999 999 (also bis 99 999,99 Euro).
  Null oder negativ ist 400 — negativ geht nur über `correct`.
- `amountCents` bei `correct`: derselbe Betrag, Vorzeichen erlaubt, null nicht.
- `pointsPerEuroX100`: 1 bis 100 000 (bis 1000 Punkte pro Euro).
- Größtes Zwischenergebnis damit rund `1e12` — passt mit Abstand in `long`.
- Höchstens 20 Prämien je Katalog, Name höchstens 40 Zeichen. Sonst wird die
  Pass-Rückseite unlesbar.

**Zurücknehmen im Einzelnen.** `undo` legt eine `REVERSAL`-Buchung mit
umgekehrtem Vorzeichen an und setzt `reversed_at` auf der Ursprungsbuchung.
War die Ursprungsbuchung ein `REDEEM`, kommen die Punkte zurück **und**
`total_rewards` geht um eins runter. Eine `REVERSAL` selbst ist nicht
zurücknehmbar — wer sie rückgängig machen will, bucht neu.

Alle mit `X-Staff-Token`, wie `/api/scan` heute. `POST` auch für `state`, weil
der QR-Inhalt nichts in einer URL und damit in Server-Logs zu suchen hat.

`undo` bekommt bewusst die **Buchungs-ID**, nicht „die letzte". Der Scanner
zeigt die Buchung an, die er zurücknehmen will, und schickt genau die mit.
Sonst nimmt bei zwei Kassen im selben Laden die eine die Buchung der anderen
zurück.

### Vorarbeit: `WalletNotifier`

`ScanController` hat den Block „SSE senden, APNs pushen, Google updaten"
heute inline. Vier neue Endpunkte würden ihn viermal kopieren. Er wandert
vorher in eine Komponente mit einer Methode. Verhalten identisch, inklusive
der Regel, dass ein fehlgeschlagener Push den Vorgang nie abbricht.

## 6. Oberflächen

### Scanner (`Scanner.jsx`)

Ablauf bleibt: scannen, dann bestätigen. Nur was dazwischen steht, hängt am
Kartentyp. Nach dem Scan fragt der Scanner `/api/scan/state`.

- **Stempelkarte:** alles wie heute, Anzahl-Wähler 1 bis 20.
- **Punktekarte:** Kunde und Stand oben, darunter das Betragsfeld, darunter
  **live das Ergebnis**: „145,00 Euro ergibt 145 Punkte". Das Personal sieht
  die Buchung, bevor es bestätigt.

Darunter drei Dinge, absteigend nach Häufigkeit: der Katalog zum Einlösen
(zu teure Prämien ausgegraut mit „noch 160"), ein Korrektur-Knopf, und die
letzte Buchung dieser Karte mit Uhrzeit plus Zurücknehmen.

**Plausibilitätsbremse:** Beträge über 500 Euro brauchen eine zweite
Rückfrage mit ausgeschriebenem Betrag. Billigste Abwehr gegen den Vertipper
(`1450` statt `145`), wegen dem die Korrektur überhaupt existiert.

### Karten-Verwaltung (`Karten.jsx`)

Beim Anlegen oben ein Umschalter Stempelkarte / Punktekarte, danach fest.
Bei Punktekarte verschwinden „Stempel bis Belohnung" und „Belohnung",
stattdessen: Kurs (Richtung wählen, Zahl eintippen), Rundungsregel, und der
Katalog als Liste aus Name und Punktkosten, Zeilen hinzufügbar und sortierbar.
Der Katalog bleibt später änderbar, nur der Typ nicht.

Bei Punktekarten entfallen die Stempel-Design-Einstellungen (Raster-Stil,
Stempel-Icon), weil sie nichts zeichnen. Farben, Logo und Banner bleiben.

**Nötige Aufräumarbeit:** `Karten.jsx` hat 860 Zeilen und enthält
`ApplePreview` und `GooglePreview`, die beide zusätzlich die Punkte-Variante
zeichnen müssen. Die Vorschauen wandern vorher nach
`src/components/CardPreview.jsx`, das Neue kommt als `PointsSettings.jsx` und
`RewardCatalog.jsx` daneben. Sonst steht die Datei bei 1200 Zeilen.

### Kundenseite

Die vom Backend gerenderte Karte unter `/karte/{customerId}/{cardId}` bekommt
eine Punkte-Darstellung: Stand, nächste Prämie, darunter der volle Katalog.
Live-Aktualisierung über denselben Weg wie heute, `CardEventHub` bekommt neben
`publishStamps` ein `publishPoints`.

Diese Seite rendert das Backend, und das hat noch keine Übersetzungstabelle
(siehe `2026-08-30-arabisch-rtl-sprache-design.md`, unerledigt). Die
Punkte-Texte kommen dort erstmal auf Deutsch dazu, wie die Stempel-Texte
heute. Im Frontend gilt die Hausregel: jeder Text in `de`, `en` und `ar`.

## 7. Wallet-Pässe

### Apple

Der Pass nutzt heute nur Kopf-, Haupt-, Neben- und Zusatzfeld.
**Rückseitenfelder gibt es bisher gar keine** — der Katalog wird das erste.
Das ist Standard für eine `storeCard`, aber der Kommentar im Code warnt zu
Recht: ein unpassendes Feld (damals `groupingIdentifier`) hat iOS schon einmal
den aktualisierten Pass verwerfen lassen.

**Harte Regel für diesen Umbau: der Stempel-Pass bleibt Feld für Feld
identisch.** Alles Neue liegt in einem Zweig, der nur bei `type == POINTS`
betreten wird.

Punkte-Pass vorne: Kopffeld `PUNKTE` mit dem Stand, Hauptfeld
`NÄCHSTE PRÄMIE` mit „noch 160", Nebenfeld mit dem Prämiennamen, Zusatzfeld
`KUNDE` wie gehabt. Hinten der Katalog, eine Zeile je Prämie mit Punktkosten
und Haken bei den erreichbaren, dazu eine Zeile mit dem Kurs.

Das `changeMessage` am Punktefeld übernimmt die Rolle von „Karte voll": es
meldet sich, wenn durch die Buchung **eine neue Prämie erreichbar geworden
ist**. Nicht bei jeder Buchung, sonst wird die Sperrbildschirm-Meldung zum
Rauschen.

Sperrbildschirm-Texte des Ladens: `{stamps}` bleibt gültig und steht bei
Punktekarten für die fehlende Zahl, neu kommt `{reward}` für den
Prämiennamen. Keine Migration, bestehende Texte funktionieren weiter.

### Google

Passt ohne neue Technik. `LoyaltyObject` hat bereits `loyaltyPoints` und
`secondaryLoyaltyPoints`, heute mit „Stempel" / „7/10" und „Belohnung"
gefüllt. Bei Punktekarten: „Punkte" / Stand und „Nächste Prämie" / „Kuchen,
noch 160". Der Katalog kommt als `textModulesData` dazu.

## 8. Statistik

`StatsService` verzweigt nach Kartentyp — Stempel und Punkte sind
verschiedene Einheiten und gehören nicht in dieselbe Säule.

Punktekarten liefern vier Zahlen, die eine Stempelkarte nicht liefern kann:

1. **Umsatz** über die gebuchten Beträge.
2. **Vergebene Punkte.**
3. **Eingelöste Prämien**, samt Rangliste welche.
4. **Ausstehender Punktebestand** — was die Kunden noch einlösen dürfen. Die
   betriebswirtschaftlich wichtigste und heute unsichtbare Zahl: die
   angesammelte Verpflichtung des Ladens.

Stoßzeiten, Tagesverlauf und aktive Kunden laufen unverändert über `ScanLog`
und funktionieren dadurch über beide Kartentypen. `ScanLog` wird bei
Punkte-Buchungen mitgeschrieben: `stampsAdded = 0` immer, `rewardsEarned = 1`
beim Einlösen, `0` sonst. Damit zählen die bestehenden Auswertungen einen
Punkte-Besuch genauso als Besuch wie einen Stempel-Scan, ohne dass Stempel-
und Punktzahlen sich vermischen. Korrekturen und Rücknahmen schreiben **kein**
`ScanLog` — sie sind kein Kundenbesuch.

## 9. Fehlerfälle

Jeder mit eigener, lesbarer Meldung — nicht dem generischen 500, der beim
Sperren-Knopf schon einmal eine Sitzung gekostet hat.

| Fall | Antwort |
|---|---|
| Punktekarte am Stempel-Endpunkt (und umgekehrt) | 400 „Diese Karte sammelt Punkte, keine Stempel" |
| Prämie nicht bezahlbar | 400 mit Stand und Preis im Klartext |
| Prämie inzwischen deaktiviert | 400, kein stiller Durchgriff |
| Buchung zweimal zurückgenommen | 400, `reversed_at` ist gesetzt |
| Versuch, eine `REVERSAL` zurückzunehmen | 400 |
| Betrag außerhalb der Grenzen aus Abschnitt 5 | 400 mit Nennung der Grenze |
| Betrag negativ bei `earn` | 400, Hinweis auf `correct` |

**Frontend neuer als Backend.** Vercel ist in zwei Minuten live, Render
braucht zwanzig. Der Scanner darf `cardType` nie voraussetzen: fehlt das Feld,
ist es ein alter Server, und er zeigt die Stempelmaske. Ein 404 auf
`/api/points/*` meldet er als „Backend-Deploy läuft noch", nicht als Fehler.

## 10. Tests

Deutsche Namen, wie die zehn bestehenden.

| Test | Nagelt fest |
|---|---|
| `PunkteRechnungTest` | Die Kurstabelle aus Abschnitt 4, alle drei Rundungsmodi, Null und Obergrenze. |
| `PunkteBuchungTest` | Buchen, Einlösen, Korrigieren, Zurücknehmen verändern den Stand richtig. Zweites Zurücknehmen scheitert. Einlösen ohne Deckung scheitert. |
| `PunkteControllerTest` | Falscher Kartentyp gibt 400, nicht 500. |
| `ApplePassPunkteTest` | **Der wichtigste.** Stempel-Pass hat weiterhin keine Rückseitenfelder, Punkte-Pass hat sie. Schutz für die Karten, die in echten Läden liegen. |
| `pointsOf.test.js` (Frontend) | Die Live-Vorschau im Scanner rechnet wie das Backend. Muster: das vorhandene `progressOf.test.js`. |

## 11. Reihenfolge

Fünf Etappen, jede einzeln lauffähig und deploybar.

1. **Modell, Rechnung, Tests.** Nichts sichtbar, `type` steht überall auf
   `STAMP`, live ändert sich nichts.
2. **Endpunkte und Buchungen**, davor der `WalletNotifier`-Auszug.
3. **Karten-Verwaltung:** Punktekarte anlegen, Katalog pflegen.
4. **Scanner:** Weiche nach Kartentyp, Punktemaske, Einlösen, Korrektur,
   Zurücknehmen.
5. **Pässe, Kundenseite, Statistik.**

Backend läuft immer vor dem Frontend, sonst steht eine fertige Oberfläche vor
einem Server, der ihre Routen nicht kennt.

## 12. Risiken

- **Apple-Pass.** Ein unpassendes Feld, und Karten aktualisieren nicht mehr.
  Bei sauber getrenntem Zweig nur die neuen, bei unsauberem auch die
  bestehenden. Dagegen steht `ApplePassPunkteTest`.
- **Render baut ohne Tests.** Was hier rot ist, geht trotzdem live. Die Suite
  läuft vor jedem Push.
- **`ddl-auto: update` auf Postgres.** Zwei neue Tabellen, vier neue Spalten,
  alle additiv und nullable oder mit Default. Geringes Risiko, aber der erste
  Deploy wird an einem neuen Feld in einer Antwort geprüft, nicht an
  `actuator/health`.
