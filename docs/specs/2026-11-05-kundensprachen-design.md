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

## Noch Deutsch

- Wallet-Karte selbst (Feldnamen, Rückseite, Mitteilungen in Apple/Google Wallet) und die Push-Texte
- Bewertungsseite `/bewertung/...` und die Rückmeldung
- Mails an den Laden (Testphase)
- Fehlerseite „Karte nicht gefunden“
