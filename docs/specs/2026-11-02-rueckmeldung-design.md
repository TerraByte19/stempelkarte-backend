# Rückmeldung (Bewertungsseite) – Design

Stand: 2026-11-02

## Ziel

Der Kunde sagt dem Laden privat, wie der Besuch war: 1–5 Sterne und ein
optionaler Satz. Der Laden sieht das im Dashboard. Danach bekommt der Kunde
den Google-Link angeboten – immer, unabhängig von den Sternen.

**Warum kein „nur bei 4–5 Sternen zu Google“:** Google verbietet es, gezielt
nur positive Bewertungen einzusammeln („review gating“). Läden, die das
machen, riskieren, dass Google Bewertungen löscht. Deshalb: der Google-Link
steht nach jeder Rückmeldung da. Der Nutzen für den Laden bleibt: Kritik
kommt zuerst privat an, und der Laden kann reagieren.

## Für den Laden

- Dashboard-Block „Rückmeldungen“ mit Schalter **Einschalten / Ausschalten**.
  Aus (Standard) = nichts ändert sich, kein Link irgendwo.
- An: Schnitt der letzten 30 Tage, Anzahl, Liste der letzten 50
  (Sterne, Text, Name, Karte, Zeit). Ungelesene fett, Klick = gelesen.

## Für den Kunden

- Seite `/bewertung/{customerId}/{cardId}` in Kartenfarbe, Logo, fünf
  Sterne, Textfeld (max. 1000 Zeichen), „Abschicken“.
- Danach „Danke“ + Google-Link (falls hinterlegt) + „Zurück zur Karte“.
- Eine Rückmeldung pro Karte und Tag. Nochmal = Hinweis „heute schon“,
  trotzdem Google-Link.
- Der Link zur Seite steht auf der Kartenseite (`/karte/...`) neben dem
  Google-Link und auf der **Rückseite der Apple-Wallet-Karte** (Feld
  „RÜCKMELDUNG“, nur wenn eingeschaltet; erscheint beim nächsten
  Kartenupdate, löst keinen eigenen Push aus). Google Wallet: noch nicht.

## Technik

- `Shop.rueckmeldung_aktiv` (Boolean, nullable, null = aus).
- Tabelle `rueckmeldungen`: id `RM-…`, shop (FK), customer_card_id (nur
  Text, kein FK – gelöschte Karten blockieren nichts), sterne, text,
  eingegangen_am, gelesen.
- Öffentlich: `GET /bewertung/{customerId}/{cardId}` (HTML),
  `POST /api/public/rueckmeldung` {customerId, cardId, sterne, text, website}.
  Honigtopf `website` wie beim Kontaktformular. Karte muss existieren (kein
  Anlegen), sonst 404. Aus = 403. Heute schon = 409.
- Laden: `GET /api/shop/rueckmeldungen` → {aktiv, ungelesen, schnitt,
  anzahl30, items}, `PUT /api/shop/rueckmeldungen/aktiv` {aktiv},
  `PUT /api/shop/rueckmeldungen/{id}/gelesen`.
- Admin-Löschen eines Ladens räumt `rueckmeldungen` mit weg.

## Nachtrag 2026-11-03: Google-Link-Hilfe und Bewertungs-Erinnerung

**Link-Hilfe:** Im Profil darf statt des Links auch nur die Place-ID des
Ladens stehen (aus Googles „Place ID Finder", z. B. `ChIJN1t_tDeuEmsRUsoyG83frY4`).
Der ShopService baut daraus `https://search.google.com/local/writereview?placeid=…`,
der direkt das Bewertungsfenster öffnet. Erkennung: nur `[A-Za-z0-9_-]`, mindestens 15 Zeichen.

**Bewertungs-Erinnerung** (`Shop.review_reminder_visits`, 0 = aus; Auswahl 2/3/5/10):
- `BewertungsErinnerungService` läuft alle 15 Minuten, ab 10 Uhr Berlin, einmal
  pro Laden und Tag. Startet eine normale Kampagne mit der internen Zielgruppe
  `BEWERTUNG` – Versand, iPhone-Auffrischen, Tageslimit pro Person und Verlauf
  laufen wie beim Geburtstagsgruß.
- Zielgruppe `BEWERTUNG`: letzter Besuch 2 Stunden bis 3 Tage her, noch nie
  erinnert (`CustomerCard.review_reminded_at`), genug Besuche. Stempelkarten:
  Stempel + Prämien × Schwelle ≥ eingestellter Wert. Punktekarten: mindestens
  eine Prämie geholt.
- Nach dem Start bekommen alle Karten der Person bei diesem Laden den Merker,
  damit es auf der zweiten Karte nicht noch einmal klingelt.
- Text: „Danke für deinen Besuch! Magst du uns bei Google bewerten? {Link}",
  bei langem Link die kurze Form, passt auch die nicht: Warnung im Log, nichts.
- Link entfernen schaltet die Erinnerung mit aus.
