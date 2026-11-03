# Kundengruppen – Design

Stand: 2026-11-03

## Ziel

Jede Kundenkarte steht automatisch in genau einer Gruppe. Der Laden sieht
auf einen Blick, wie viele Stammgäste er hat und wer gerade abspringt, und
kann die Kundenliste danach filtern. Die Regeln (ab wann Stammgast, ab wann
ruhig, ab wann verloren) stellt er im Profil ein.

## Gruppen

Reihenfolge der Prüfung, die erste Regel, die passt, gewinnt:

1. **VERLOREN** – letzter Besuch länger als `verlorenTage` her (Standard 90).
2. **WIRD_RUHIG** – letzter Besuch länger als `ruhigTage` her (Standard 30).
3. **STAMMGAST** – mindestens `stammAb` Besuche (Standard 5).
4. **NEU** – Karte in den letzten 14 Tagen angelegt (wie Zielgruppe NEU).
5. **GELEGENTLICH** – alles andere.

Stammgast vor Neu: wer in zehn Tagen sechsmal da war, ist Stammgast. Ruhig
und Verloren gelten für alle, auch für Stammgäste – genau das will der
Laden sehen („mein Stammgast kommt nicht mehr“).

Letzter Besuch = `CustomerCard#letzterBesuch()` (lastVisitAt, sonst
updatedAt), wie bei den Zielgruppen.

Besuche: Stempelkarte = Stempel + Prämien × Schwelle (dieselbe Formel wie
bei der Bewertungs-Erinnerung, deckt die Zeit vor dem ScanLog ab).
Punktekarte = Buchungen im ScanLog dieser Karte (eine Gruppen-Abfrage je
Laden, kein N+1).

## Regeln

`shops.gruppe_stamm_ab`, `gruppe_ruhig_tage`, `gruppe_verloren_tage`
(Integer, nullable = Standard). Bereich: Stammgast 2..50 Besuche, ruhig
7..180 Tage, verloren 14..365 Tage, verloren > ruhig. Prüfung in
`Kundengruppe.Regeln#pruefe`, 400 bei Verstoß.

## API

- `GET /api/shop/kunden?gruppe=NEU` filtert. Ohne `gruppe` läuft der alte
  Weg (DB-Paging) unverändert. Mit `gruppe` werden alle aktiven Karten des
  Ladens geladen, in Java eingeordnet, gesucht und seitenweise geschnitten
  – dieselbe Sortierung (letzter Besuch zuerst). Jede Zeile bekommt
  `gruppe`.
- `GET /api/shop/kunden/gruppen` → `{ zahlen: {NEU: n, …}, regeln: {…} }`.
  Zählt Kundenkarten, nicht Personen – wie die Liste.
- `PUT /api/shop/kunden/gruppen` `{stammAb, ruhigTage, verlorenTage}`.

## Oberfläche

- Kundenliste: Chips mit Zahlen über der Liste (Alle, Neu, Stammgast,
  Gelegentlich, Wird ruhig, Verloren), Klick filtert; Gruppe als farbige
  Marke an jeder Zeile; `?gruppe=` in der URL, damit das Dashboard
  verlinken kann.
- Dashboard: kleiner Block „Kundengruppen“ mit den fünf Zahlen als Links.
- Profil: Block „Kundengruppen“ mit den drei Regeln.
- Älteres Backend (404 auf /gruppen): Chips und Block bleiben unsichtbar.

## Bewusst nicht

Keine neuen Kampagnen-Zielgruppen aus den Gruppen: „30/60/90 Tage nicht
da“ und „Stammgast wird ruhig“ gibt es dort schon. Kein Push beim
Gruppenwechsel.
