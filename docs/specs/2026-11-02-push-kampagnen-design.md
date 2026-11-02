# Push-Kampagnen

Stand: 2026-11-02. Baut auf der Rueckhol-Nachricht auf (RueckholService, Feld `nachricht`).

## Ziel

Der Laden schreibt eine kurze Nachricht und schickt sie als Mitteilung auf die
Wallet-Karten einer Zielgruppe seiner Kunden (iPhone und Android). Vorschau,
Empfaengerzahl je Gruppe, Verlauf.

## Entscheidungen

- **Zielgruppen** (service.Zielgruppe), gefiltert in Java ueber die Grundmenge
  "aktive Karten, Geschenk nicht offen":
  ALLE, NEU (Karte < 14 Tage), INAKTIV_30/60/90 (letzter Besuch aelter),
  EINMAL (genau ein Stempel, 14 Tage weg, nur Stempelkarten), STAMM_RUHIG
  (mindestens eine Praemie geholt, 21 Tage weg), PRAEMIE_WARTET (Karte voll
  bzw. genug Punkte fuer die naechste Praemie), FAST_VOLL (noch 1-2 Stempel).
  Kein Geburtstag: wird bei der Anmeldung nicht abgefragt.
- **Jede Karte hoechstens eine Kampagne pro Kalendertag** (Europe/Berlin).
  Apple laesst rund 3 Pushes pro Karte und Tag durch; zwei bleiben fuer
  Stempel. Der Laden darf mehrere Kampagnen an verschiedene Gruppen schicken,
  wer heute schon eine hatte, wird uebersprungen (im Dashboard als
  "n heute schon benachrichtigt" sichtbar).
- **Empfaenger beim Start festgeschrieben** (Tabelle `kampagne_empfaenger`):
  "30 Tage nicht da" trifft genau die, die es in dem Moment waren.
- **Keine Uhrzeit-Sperre.** Der Laden drueckt bewusst auf Senden.
- **Text 1 bis 160 Zeichen**, Steuerzeichen werden zu Leerzeichen.
- **Planen** (`geplantAm`, 1 Minute bis 90 Tage voraus): Status GEPLANT ohne
  Empfaengerzeilen. Der Laeufer bestimmt die Empfaenger erst zum Zeitpunkt
  (`aktiviere`) - "30 Tage nicht da" trifft die, die es dann sind. Niemand da
  -> FERTIG mit 0. Geplante lassen sich loeschen (`DELETE /{id}`), laufende
  nicht. `nachrichtenSeit` wird schon beim Planen gesetzt: jeder Stempel bis
  dahin bringt das Feld auf ein weiteres iPhone.
- **Ergebnis** (`wiedergekommen`, `ergebnisAm`): wie viele Empfaenger
  (Personen) in den 7 Tagen ab Versand mindestens einmal gescannt haben,
  ueber ScanLog. Solange das Fenster laeuft, rechnet `GET` live
  ("bisher n"); danach schreibt der Laeufer den Wert fest. Keine
  Kontrollgruppe - das waere der naechste Schritt.
- **Kontrollgruppe** (`kontrollgruppe` im Request): ab 10 Empfaengern geht
  ein zufaelliges Zehntel (mindestens eine Karte) als Kontrollzeile in
  `kampagne_empfaenger` (`kontrolle = true`, von Anfang an erledigt, also
  nie gepusht). `Kampagne.kontrolleEmpfaenger` zaehlt sie; beim Planen steht
  dort -1 als Merker, aufgeteilt wird erst beim Aktivieren. Das Ergebnis
  zaehlt beide Gruppen getrennt (`wiedergekommen`, `kontrolleWiedergekommen`).
- **Vorlagen und Entwurf** sind reine Frontend-Sache: vier Texte in der
  Sprache der Oberflaeche (Happy Hour, neues Produkt, Event, Angebot mit
  Laufzeit), der Entwurf liegt im localStorage des Browsers.

## Wie die Nachricht ankommt

### Apple
Das Rueckseitenfeld `nachricht` (bisher nur bei eingeschalteter Rueckhol-
Funktion) steht in jedem Pass eines Ladens, der Rueckhol an hat ODER schon
einmal eine Kampagne geschickt hat (`Shop.nachrichtenSeit`, bleibt gesetzt).
Wert = neueste Nachricht auf der Karte (Rueckhol solange sichtbar, sonst
Kampagne), sonst Ruhetext. `changeMessage("%@")` nur, wenn die Nachricht
juenger ist als der letzte Besuch - sonst meldet iOS beim naechsten Stempel
die alte Nachricht noch einmal.

iOS meldet eine Aenderung nur an einem Feld, das der Pass auf dem Handy schon
hat. Karten mit iPhone, deren Pass vor `nachrichtenFeldSeit()` gebaut wurde,
werden deshalb zuerst still aufgefrischt (updatedAt bewegen, Push ohne
Nachricht) und bekommen die Nachricht im naechsten Lauf. Das kostet diese
Karten einmalig einen zweiten Push.

Kopf-Key `stamps` / `stamps-header` bleibt unberuehrt. Pass-Cache: der Versand
bewegt `updatedAt`.

### Google
`GoogleWalletService.sendeNachricht(..., "kampagne")` mit TEXT_AND_NOTIFY,
Titel = Ladenname. Kein Objekt-Patch noetig.

## Versand

`KampagnenService.starte(shop, text, gruppe)`: Text pruefen, Gruppe aufloesen,
`shop.nachrichtenEinschalten(jetzt)`, Empfaenger = Grundmenge gefiltert nach
Gruppe und ohne "heute schon", leer -> 400. Kampagne LAEUFT plus eine Zeile
pro Karte in `kampagne_empfaenger`.

Laeufer `lauf()` alle 30 s (Spring `@Scheduled`, eine Instanz), pro laufender
Kampagne eine Seite von 25 offenen Zeilen:

1. Transaktion: je Zeile die Karte laden (fehlt sie: abhaken).
   - iPhone registriert, Feld nicht auf dem Handy, noch nicht aufgefrischt
     -> `passAuffrischen`, Zeile bleibt offen (`aufgefrischt_am` gesetzt)
   - sonst -> `kampagneErhalten(text, jetzt)`, Zeile erledigt
2. Danach Pushes: APNs fuer Karten mit iPhone, Google fuer die mit Text.
3. Keine offene Zeile mehr -> Kampagne FERTIG.

Nach einem Neustart laufen Kampagnen mit Status LAEUFT einfach weiter - der
Fortschritt steht in den Zeilen, nicht im Thread.

## Daten

`Shop`: `nachrichten_seit` (timestamp, nullable). Gesetzt beim Einschalten der
Rueckhol-Funktion (wenn leer) und beim Start der ersten Kampagne.
`hatNachrichtenFeld()` = Rueckhol aktiv oder `nachrichtenSeit` gesetzt.
`nachrichtenFeldSeit()` = fruehester Zeitpunkt, seit dem Paesse das Feld tragen.

`CustomerCard`: `kampagne_text` (varchar 300), `kampagne_am` (timestamp), beide
nullable. `kampagneErhalten(text, jetzt)` wie `rueckholGesendet`.
`nachrichtText()` / `nachrichtFrisch()` entscheiden, was im Feld steht.

`kampagnen`: id `KP-...`, shop_id, text (160), gruppe (24, null = ALLE),
status (RUNNING/DONE), empfaenger, gesendet, erstellt_am, fertig_am.

`kampagne_empfaenger`: id `KE-...`, kampagne_id, customer_card_id (nur IDs,
kein Fremdschluessel), erledigt, aufgefrischt_am.

Schema per `ddl-auto: update`. Beim Loeschen eines Ladens (Admin) werden
Kampagnen und Zeilen mit entfernt.

## API

`KampagnenController`, `/api/shop/kampagnen`, JWT wie ShopController.

- `GET` -> `{ textMax, gruppen: [{gruppe, empfaenger, heuteSchon}],
  items: [{id, text, gruppe, status, empfaenger, gesendet, erstelltAm, fertigAm}] }`
  (neueste 20).
- `POST` `{text, gruppe}` -> 200 mit dem Kampagnen-Eintrag.
  400 bei leerem/zu langem Text, unbekannter Gruppe oder leerer Empfaengerliste
  (Fehlertext kommt vom Server und wird im Dashboard angezeigt).

## Dashboard

`KampagnenSection` zwischen Scanner-Einrichtung und Newsletter: Zielgruppen
als Liste mit Zahl rechts, Textfeld mit Zaehler (160), Vorschau als
iPhone-Sperrbildschirm-Mitteilung, Knopf "An N Kunden senden" mit Rueckfrage,
Verlauf mit Gruppe und Status; waehrend RUNNING alle 3 s nachladen (max 2
Minuten). Texte de/en/ar. Altes Backend (404): Block bleibt unsichtbar.

## Tests

- `KampagnenKarteTest`: nachrichtText/nachrichtFrisch, Kuerzen, Shop-Feldregeln.
- `KampagnenFeldTest`, `RueckholFeldTest`: Apple-Feld.
- `ZielgruppeTest`: jede Gruppe, "jetzt" in die Zukunft geschoben.
- `KampagnenServiceTest`: Tuer (Text, Gruppe, niemand), Empfaenger festschreiben,
  heute schon, nur Google / iPhone alt / iPhone mit Feld, geloeschte Karte,
  fertig, Push-Fehler.
- `KampagnenAbfragenTest` (@DataJpaTest): Grundmenge, Zeilen, Verlauf, Loeschen.
- `KampagnenControllerTest`.
