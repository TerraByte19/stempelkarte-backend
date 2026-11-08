# Tarife im Backend (2026-11-04)

## Ziel

Die Preisseite verspricht Basic, Pro und Kette. Das Backend setzt das jetzt um:
Basic bekommt die Pro-Funktionen nicht, und bei 200 Kunden kommt ein Hinweis.

## Entscheidungen

- `Shop.tarif` (Spalte `tarif`, nullable): BASIC, PRO oder KETTE. Der Admin setzt den Wert.
- **Ohne Eintrag gilt Pro.** Bestandsläden verlieren nichts. Neue Läden testen in den 14 Gratis-Tagen alles. Nach dem Abschluss stellt der Admin den Tarif um.
- Basic hat nicht: Kampagnen, Auto-Nachrichten (dazu gehört der Geburtstagsgruß), Automationen (Baukasten), Tagesangebot, Newsletter, API/Webhooks und Filialen.
- Basic behält: alle Kartentypen, Design, Stufen, Kunde wirbt Kunde, Aufkleber, Team, Kundenliste, Gruppen, Statistik, Bewertungen und Rückmeldung (auch die Bewertungs-Erinnerung), Ortsmitteilung, Stempel-Mitteilung und Rückhol-Nachricht.
- Kette verhält sich wie Pro. Wie viele Filialen Pro oder Kette haben, setzt der Admin weiter über `maxOrte`, weil weitere Filialen extra kosten.
- **Kundengrenze Basic 100:** Ab 200 Kunden kommt nur ein Hinweis. Nichts wird gesperrt, neue Kunden melden sich weiter an.

## Was gesperrt wird

Gesperrt wird nur das Einschalten oder Versenden. Ausschalten, Löschen und Ansehen gehen immer, damit ein Laden nach dem Wechsel aufräumen kann.

| Stelle | Sperre |
|---|---|
| `POST /api/shop/kampagnen` | gesperrt |
| Geplante Kampagne wird fällig, Laden ist inzwischen Basic | wird nicht gesendet, fertig mit 0 Empfängern |
| `PUT /api/shop/auto-nachrichten/{typ}` mit aktiv=true | gesperrt (aus geht) |
| `PUT /api/shop/me/geburtstag` mit enabled=true | gesperrt (aus geht) |
| `POST /api/shop/automationen` | gesperrt |
| `PUT /api/shop/automationen/{id}` | gesperrt, außer aktiv=false |
| `PUT /api/shop/tagesangebot` | gesperrt |
| `POST /api/shop/newsletter` und `/newsletter/test` | gesperrt |
| `POST /api/shop/api-keys` und `PUT /api/shop/webhook` | gesperrt |
| API-Schlüssel (`X-Api-Key`) | werden abgelehnt (`Shop.isApiAktiv`) |
| Webhook-Ereignisse | werden nicht mehr geschrieben |
| `POST /api/shop/me/orte` | gesperrt |
| Weitere Orte im Pass und bei der Geo-Prüfung | zählen nicht (`OrteService.erlaubt` = 0) |
| Läufer für Auto-Nachrichten, Automationen und Geburtstag | überspringen Basic-Läden |

Die Einstellungen bleiben gespeichert. Nach einem Wechsel zurück auf Pro laufen sie weiter. Auch `apiFreigeschaltet` und `maxOrte` bleiben stehen.

**Antwort bei Sperre:** HTTP **402** mit `{"error": "Das gibt es ab dem Tarif Pro. …", "code": "TARIF", "funktion": "KAMPAGNEN"}`.
Der Status ist bewusst nicht 403, weil `src/api.js` im Frontend bei 403 ausloggt.

## Endpunkte

- `GET /api/shop/tarif` liefert `{tarif, kundenGrenze, kunden, grenzeErreicht, funktionen: {kampagnen, auto_nachrichten, automationen, tagesangebot, newsletter, api, filialen}}`.
- `GET /api/shop/me` hat zusätzlich das Feld `tarif`.
- `POST /api/admin/shops/{id}/tarif` mit `{tarif: "BASIC"}` antwortet mit `{id, tarif, kundenGrenze, hinweis?}`.
  - `hinweis` kommt, wenn ein Basic-Laden weitere Orte hat, denn Scans dort gelten dann als außerhalb des Ladens.
- Die Admin-Liste `GET /api/admin/shops` hat zusätzlich `tarif` und `kundenGrenze`.

## Frontend offen

- **Admin:** Auswahl Basic/Pro/Kette je Laden. Kunden als „112 / 100“ anzeigen, rot ab der Grenze. Den `hinweis` anzeigen.
- **Dashboard:** bei `grenzeErreicht` ein Hinweis-Streifen: „Du hast 200 Kunden erreicht. Neue Kunden können sich weiter anmelden. Mit Pro hast du unbegrenzt viele.“
- **Nachrichten, Team, Profil:** Funktionen mit `funktionen.x = false` zeigen ein Schloss „Ab Pro“ statt der Formulare. Bestehende Einträge bleiben sichtbar und lassen sich ausschalten oder löschen.
- Bei einer 402-Antwort mit `code: TARIF` die Meldung freundlich zeigen, nicht als Fehler.

## Testphase (14 Tage gratis)

- `Shop.testBis` (Spalte `test_bis`, nullable). `ShopService.register` setzt jetzt + 14 Tage. Null = keine Testphase (bezahlt oder Bestandsladen), dann wird nie gesperrt.
- **Nach Ablauf gesperrt** (User-Entscheidung 2026-11-04, „dann kann ich verlängern“):
  - Scannen: `/api/scan`, `/scan/aufladen`, `/scan/willkommen`, `/api/points/earn` und `/correct`
  - alle Funktionen hinter `TarifPruefung.pruefe`
  - die Läufer, auch Rückhol-Nachricht und Bewertungs-Erinnerung
- **Weiter möglich:** Login, Dashboard, Kundenliste, Prämie einlösen und Karte zurücksetzen. Die Wallet-Karten bleiben, gelöscht wird nichts.
- **Antwort:** 402 mit `{"error": "Die Gratis-Testphase ist vorbei. …", "code": "TEST_VORBEI"}`.
- **Admin:** `POST /api/admin/shops/{id}/test` mit `{tage}`. 0 = bezahlt, 1 bis 90 = ab heute verlängern. Die Admin-Liste zeigt `testBis` und `testTage`.
- `/api/shop/me` liefert `testBis`, `testTage` (verbleibende Tage, 0 = vorbei) und `testVorbei`. `/api/shop/tarif` liefert `testVorbei`.
- **Frontend (gebaut):** Admin-Auswahl „Test: X T / Test vorbei / Als bezahlt markieren / +7 / +14“. Dashboard-Streifen: blau mit Resttagen, rot wenn vorbei.
- **Erinnerungs-Mail** (TestphaseErinnerungService, alle 30 Minuten, nur 9 bis 20 Uhr Berlin): 3 Tage vor dem Ende „endet am …“, nach dem Ende „ist vorbei“. Jede nur einmal, gemerkt in `Shop.testMailStufe` (Spalte `test_mail_stufe`). Es zählt nur eine Mail, die der Mailserver angenommen hat. Verlängert der Admin, beginnt es von vorn. Knopf „Zum Dashboard“ (`FRONTEND_URL` + /dashboard).


**Änderung 2026-11-08:** Basic 29 € statt 39 €, Grenze 200 Kunden statt 100 (`Tarif.BASIC_KUNDEN`), jede weitere Filiale bei Pro 29 € statt 39 €.
