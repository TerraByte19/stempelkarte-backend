# Kundenseiten und Mails in vier Sprachen (2026-11-05)

Setzt die Spezifikation `2026-08-30-arabisch-rtl-sprache-design.md` um, erweitert auf
Deutsch, Englisch, Arabisch und Türkisch (die Sprachen der Laden-App).

## Was der Kunde jetzt in seiner Sprache sieht

- Anmeldeseite `/karte-neu/{cardId}` (Überschrift, Felder, Einwilligung, Knopf, „Fast geschafft“, Fehlertexte)
- Kartenseite `/karte/{customerId}/{cardId}` für Stempel-, Punkte-, Mitglieds- und 10er-Karte, inklusive Live-Texte im JS,
  Willkommensbox, Wallet-Knöpfe, Status-Zeile, Demo-Block, Freunde-werben-Block, Bewertungslinks
- Seiten hinter Mail-Links `/mail/*` (bestätigt, Löschung, abgemeldet)
- Bestätigungs-Mail, Lösch-Mail, Rahmen und Stempelstand im Newsletter

## Sprache

- `Shop.language` (de, en, ar, tr): im Admin beim Anlegen oder nachträglich per Auswahl in der Tabelle
  (`POST /api/admin/shops/{id}/sprache {language}`). Bestandsläden ohne Eintrag = Deutsch, nichts ändert sich.
- `Customer.language` (neu, Spalte `language`, nullable): beim ersten Anmelden vom Laden übernommen und danach fest.
  Ein Kunde, der bei einem deutschen Laden angefangen hat, bleibt deutsch, auch wenn der Laden später umstellt.
- Reihenfolge: Kunde, dann Laden, dann Deutsch. Arabisch ist rechts-nach-links (`<html lang dir>`, in Mails auch an den Tabellen).

## Texte

- `i18n/Texts.java`, generiert aus `docs/i18n/kunden_de_en.json`, `kunden_ar.json`, `kunden_tr.json`
  mit `node docs/i18n/gen_texts.mjs`. 91 Schlüssel. Platzhalter `{0}`, `{1}`.
- Texte, die ins Inline-JS gehen, laufen über `toJsString`; Knopftexte in `onclick`-Attributen stehen als
  `data-*`-Attribute, damit ein Apostroph (türkisch „Stampit’i“) nichts kaputt macht.
- `TextsTest` prüft: jede Sprache hat jeden Schlüssel, dieselben Platzhalter, kein Geviertstrich.
  `KundenSprachenTest` baut die echten Seiten in tr, ar, en und de.

## Nachtrag (Nacht auf 2026-11-05)

Jetzt auch in der Sprache des Kunden:
- Wallet-Karte (Apple: Feldnamen, Rückseite, changeMessages mit `%@`; Google: Labels, Textbausteine, Mitteilungen)
- Sperrbildschirm-Erinnerung, Rückhol-Standardtext, Stufen-Text, Paket-Rest, Freunde-werben-Satz
- Bewertungsseite `/bewertung/...` mit ihren Hinweisseiten
- Marke überall „Stampit“ (vorher teils „StampIT“)

Texte: 192 Schlüssel in `docs/i18n/*.json`. Neue Schlüssel kommen als `neu_de_en.json` + `neu_ar.json` + `neu_tr.json`
daneben, `node docs/i18n/gen_texts.mjs` mischt sie ein. Tests: `WalletSprachenTest`, `RueckholSprachenTest`.

## Noch Deutsch

- Mails an den Laden (Testphase)
- Fehlerseite „Karte nicht gefunden“ bei kaputtem Link
- Scanner-Meldungen (`ScanResult`) für das Personal

## Änderung 2026-11-05 morgens (User): Standard Deutsch, der Kunde schaltet um

- Jeder Kunde startet auf **Deutsch**, egal bei welchem Laden. `Shop.language` spielt für Kundenseiten, Wallet und Mails keine Rolle mehr (im Admin nur noch Anzeige).
- Anmeldeseite und alle Kartenseiten haben unten eine Leiste **DE · EN · AR · TR** (`?lang=`). Auf der Anmeldeseite wird nur eine ausdrückliche Wahl mitgeschickt (`language` im Formular, sonst `null`) und beim Kunden gespeichert; auf der Kartenseite speichert `?lang=` sofort (`CustomerService.spracheSetzen`).
- Danach gilt die gespeicherte Sprache für Karte, Wallet-Pass, Mitteilungen und Mails (`Texts.sprache(customer, shop)` = Kunde oder Deutsch).
- Tests: `KundenSpracheTest`, `KundenSprachenTest` (Leiste, `?lang=ar`, `const LANG`), Wallet-/Kiosk-/Rückhol-Tests setzen die Sprache am Kunden.
