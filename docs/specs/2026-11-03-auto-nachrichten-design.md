# Automatische Nachrichten – Design

Stand: 2026-11-03 (nur Backend; Frontend folgt im Sammel-Durchgang)

## Ziel

Der Laden schaltet fertige Automaten ein, die ohne sein Zutun die passende
Nachricht aufs Handy schicken. Fünf Auslöser, je ein Schalter und ein
eigener Text (leer = Standardtext):

| Typ | Wer | Wann | Wie oft |
|---|---|---|---|
| WILLKOMMEN | hat gesammelt, noch keine Prämie, Karte ≤ 14 Tage alt | 20 h bis 3 Tage nach dem Besuch | einmal pro Person und Laden |
| ZWEITER_BESUCH | Stempelkarte, ≥ 2 Stempel, keine Prämie, Besuch ≥ 20 h nach Anlage | 20 h bis 3 Tage nach dem Besuch | einmal pro Person und Laden |
| FAST_VOLL | Stempelkarte, noch 1–2 Stempel | 2 bis 10 Tage nach dem Besuch (nicht zurückgekommen) | einmal pro Prämien-Runde |
| PRAEMIE_BEREIT | Karte voll bzw. genug Punkte (wie PRAEMIE_WARTET) | 2 bis 14 Tage nach dem Besuch | einmal pro Prämien-Runde |
| VIP | Stempel: ≥ 2 × „Stammgast ab“ Besuche; Punkte: ≥ 2 Prämien; zuletzt da innerhalb „wird ruhig“-Tage | ab 10 Uhr | einmal im Monat pro Laden |

Alle laufen als normale Kampagne mit interner Zielgruppe
(`Zielgruppe.AUTO_*`, nicht wählbar) – Versand, iPhone-Auffrischen,
Tageslimit pro Person und Verlauf wie Geburtstag und Bewertung.

FAST_VOLL und PRAEMIE_BEREIT kommen bewusst nicht sofort: direkt nach dem
Stempel sagt der Stempel-Push schon „noch 2“ bzw. „Prämie verdient“. Die
Automaten erinnern erst, wenn der Kunde ein paar Tage nicht zurück war.

## Platzhalter

Nachrichtentexte (auch von Hand gestartete Kampagnen) dürfen enthalten:

- `{name}` – Vorname des Kunden (erstes Wort), sonst leer
- `{offen}` – fehlende Stempel bis zur Prämie (Punktekarte: leer)
- `{praemie}` – Prämientext der Stempelkarte bzw. nächste erreichbare
  Prämie der Punktekarte

Gefüllt wird je Karte beim Versand (`Platzhalter.fuelle`, in
`KampagnenService#markiere`). Die Kampagne im Verlauf behält die Vorlage.
Die 160-Zeichen-Grenze gilt für die Vorlage; der gefüllte Text darf etwas
länger sein (Rückseite 300, Google-Mitteilung frei).

## Technik

- `auto_nachrichten` (AutoNachricht): id, shop, typ, aktiv, text (160,
  null = Standard), updated_at. Unique (shop, typ).
- Merker auf `customer_cards`: `auto_willkommen_am`, `auto_zweiter_am`
  (Instant, pro Person: alle Karten der Person bei diesem Laden),
  `auto_fast_voll_bei`, `auto_praemie_bei` (Integer = totalRewards beim
  Versand; neue Runde = wieder fällig). VIP braucht keinen Merker (eine
  Kampagne pro Laden und Monat).
- `AutoNachrichtenService`: @Scheduled alle 15 min, ab 10 Uhr, je aktivem
  Automat einmal pro Tag (VIP: pro Monat), Prüfung über
  `existsByShopAndGruppeAndErstelltAmGreaterThanEqual`, Merker im Speicher
  spart die Abfrage. Danach Merker auf die Empfängerkarten.
- API: `GET /api/shop/auto-nachrichten` → fünf Einträge {typ, aktiv, text,
  standard}; `PUT /api/shop/auto-nachrichten/{typ}` {aktiv, text}.
- Admin-Löschen räumt `auto_nachrichten` mit ab.

## Frontend offen

- Profil (oder späterer Bereich „Nachrichten“): Block „Automatische
  Nachrichten“ mit fünf Zeilen: Schalter, Textfeld mit Standard als
  Platzhalter, Hinweis zu `{name}` `{offen}` `{praemie}`.
- Kampagnen-Verlauf: Namen für die Gruppen `AUTO_WILLKOMMEN`,
  `AUTO_ZWEITER_BESUCH`, `AUTO_FAST_VOLL`, `AUTO_PRAEMIE`, `AUTO_VIP`
  (`kamp_g_*`).
- Kampagnen-Formular: Hinweis auf die Platzhalter.
