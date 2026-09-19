# StampIT Backend

Spring Boot 3.5 / Java 21 / Maven. Digitale Stempelkarten in Apple und Google Wallet.
Frontend liegt im Repo `stempelkarte-frontend` und wird oft zusammen geaendert.

## Befehle

```bash
./mvnw -o test        # komplette Suite, vor jedem Push
./mvnw -o compile     # nur uebersetzen
```

`-o` (offline) spart Zeit, solange keine neue Abhaengigkeit dazukam.

## Deploy

Push auf `main` startet Render (Docker, `mvn clean package -DskipTests`).
Tests laufen dort also NICHT - was hier rot ist, geht trotzdem live.

**Ein Deploy ist nicht bestaetigt, nur weil `actuator/health` antwortet.**
Das beweist bloss, dass ein Prozess laeuft, nicht welcher Commit. Zum
Pruefen etwas nehmen, das der neue Stand hat und der alte nicht: einen
neuen Endpunkt in `/v3/api-docs`, oder ein neues Feld in einer Antwort.
Render braucht regelmaessig 10-20 Minuten.

## Datenbank

H2 im Test, Postgres in Produktion, `ddl-auto: update`.

Neue Spalten deshalb **nullable oder mit Default**, sonst scheitert das
Update an vorhandenen Zeilen:

```java
@Column(name = "failed_count", nullable = false, columnDefinition = "integer default 0")
```

`open-in-view: false`. Lazy geladene Beziehungen ausserhalb einer
Transaktion fliegen dir um die Ohren. Wer in einem Repository-Aufruf mehr
als die Entity selbst braucht, setzt `@EntityGraph`.

## Hintergrundarbeit

`AsyncConfig` deklariert `applicationTaskExecutor` **und**
`newsletterExecutor` von Hand. Grund: sobald irgendein eigenes
Executor-Bean existiert, legt Spring Boot seinen Standard-Pool nicht mehr
an, und dann laufen alle `@Async`-Aufrufe durch den einen Newsletter-
Thread. `AsyncConfigTest` haelt das fest.

Newsletter-Versand laeuft als ein Hintergrund-Job, der Reihe nach, und
schreibt sein Ergebnis in `SentNewsletter` (gesendet, fehlgeschlagen,
Status). Nicht Versuche zaehlen - nur was der Mailserver angenommen hat.

## Mails

Layout liegt auf verschachtelten Tabellen mit Inline-Styles. **Kein
`display:flex`, kein `gap`** - Gmail und Outlook werfen beides weg.
`MailVorlageTest` prueft das.

Kein Hero-Bild in Mails: das ist der Apple-Wallet-Streifen, hart auf
1125x369 zugeschnitten, und damit in einer Mail ein angeschnittenes Logo.
Stattdessen das Markenband in den Farben des Kartendesigns.

## Stil

- Kommentare auf Deutsch und sie erklaeren das **Warum**, nicht das Was.
  Besonders wertvoll: welcher Fehler dahintersteckte, welche Falle es gibt.
- Tests tragen deutsche Namen und im Klassenkommentar steht, welches
  Verhalten sie festnageln und warum es vorher kaputt war.
- Umlaute in Code-Kommentaren werden umschrieben (ae, oe, ue), in Texten
  fuer Nutzer NICHT.
- In Texten, die Nutzer sehen: **keine Geviertstriche (—)**, normale
  Bindestriche verwenden.

## Umgang

Nicht pushen, ohne dass danach gefragt wurde. Erst Tests, dann berichten,
was gruen ist - auch wenn etwas rot blieb.
