# Punktekarte, Teil 2: Laden-App — Umsetzungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der Laden kann eine Punktekarte anlegen und ihren Prämien-Katalog pflegen, und das Personal kann am Scanner Einkäufe buchen, Prämien einlösen, korrigieren und Fehlbuchungen zurücknehmen.

**Architecture:** Der Scanner fragt nach jedem Scan `/api/scan/state` und entscheidet daran, welche Maske er zeigt. Die Stempelmaske bleibt Zeile für Zeile wie sie ist, die Punktemaske kommt daneben. Die Umrechnung Euro nach Punkten liegt als reine Funktion in `pointsOf.js` und spiegelt `PointsMath` im Backend, damit die Live-Vorschau dasselbe rechnet wie die Buchung.

**Tech Stack:** React 19 + Vite, kein TypeScript, kein CSS-Framework. Styles sind Inline-Objekte in einem `styles`/`s`-Objekt am Dateiende. Tests laufen über `node --test`.

**Repo:** `C:\Project SK\stempelkarte-frontend`
**Spec:** `Stemplekarte/docs/specs/2026-09-21-punktekarte-design.md`
**Setzt voraus:** Teil 1 ist deployt. Ohne die Backend-Routen läuft hier nichts.

## Global Constraints

- **Jeder sichtbare Text braucht alle drei Sprachen: `de`, `en`, `ar`.** Ein fehlender Schlüssel fällt beim Build nicht auf, er steht dann roh in der Oberfläche.
- **Arabisch läuft RTL.** Layouts nicht auf links/rechts festnageln. Neue arabische Texte mit `// TODO ar-review` markieren, wie im Arabisch-Spec vereinbart: vor echtem Go-Live liest ein Muttersprachler gegen.
- **Keine Geviertstriche** in Nutzertexten. Normale Bindestriche.
- **Nichts behaupten, was die App nicht kann.**
- **Das Frontend ist regelmäßig neuer als das Backend** (Vercel 2 Minuten, Render 10 bis 20). Neue Felder in einer Antwort nie voraussetzen: `const typ = res.data.type ?? 'STAMP'`. Ein 404 auf `/api/points/*` ist meistens kein Fehler im Panel, sondern ein laufender Backend-Deploy — dem Nutzer auch so sagen.
- **Fehler sichtbar machen.** `Admin.jsx` hat ein `error`, das nur auf dem Login-Bildschirm gerendert wird; wer im eingeloggten Panel darauf schreibt, produziert eine unsichtbare Meldung. Für neue Fehlerfälle einen eigenen Zustand plus Banner anlegen (Muster: `sortError`).
- **Geteilte Ansichten liegen in `src/components/`.** Nicht kopieren.
- **Kommentare auf Deutsch, sie erklären das Warum.** Umlaute darin umschreiben, in Nutzertexten nicht.
- **Vor jedem Push `npm run build`.** Nicht pushen, ohne dass danach gefragt wurde.
- Aus einer Cloud-Sitzung (Handy, Browser) geht der Push auf einen eigenen Branch, nie direkt auf `main`.

---

### Task 1: Umrechnung als reine Funktion

Zuerst, weil die Live-Vorschau im Scanner daran hängt und weil eine Abweichung zum Backend genau das Vertrauen zerstört, das die Vorschau herstellen soll.

**Files:**
- Create: `src/lib/pointsOf.js`
- Test: `src/lib/pointsOf.test.js`

**Interfaces:**
- Consumes: nichts.
- Produces:
  - `punkteFuer(amountCents, pointsPerEuroX100, rounding) -> number` (Hundertstel-Punkte)
  - `formatierePunkte(pointsX100) -> string`
  - `centsAusEingabe(text) -> number|null`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`src/lib/pointsOf.test.js`:

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { punkteFuer, formatierePunkte, centsAusEingabe } from './pointsOf.js'

// Dieselben Faelle wie PunkteRechnungTest im Backend. Weicht eine der
// beiden Seiten ab, zeigt der Scanner dem Personal eine andere Zahl an,
// als danach gebucht wird - und genau dieses Vertrauen soll die
// Live-Vorschau ja herstellen.

test('1 Euro = 1 Punkt, genau', () => {
  assert.equal(punkteFuer(520, 100, 'GENAU'), 520)
})

test('1 Euro = 5 Punkte, genau', () => {
  assert.equal(punkteFuer(520, 500, 'GENAU'), 2600)
})

test('5 Euro = 1 Punkt, genau', () => {
  assert.equal(punkteFuer(520, 20, 'GENAU'), 104)
})

test('2 Euro = 3 Punkte, genau', () => {
  assert.equal(punkteFuer(520, 150, 'GENAU'), 780)
})

test('abrunden schneidet auf ganze Punkte', () => {
  assert.equal(punkteFuer(570, 100, 'ABRUNDEN'), 500)
})

test('kaufmaennisch rundet ab der Haelfte auf', () => {
  assert.equal(punkteFuer(570, 100, 'KAUFMAENNISCH'), 600)
  assert.equal(punkteFuer(520, 100, 'KAUFMAENNISCH'), 500)
  assert.equal(punkteFuer(550, 100, 'KAUFMAENNISCH'), 600)
})

test('negativer Betrag rundet symmetrisch', () => {
  assert.equal(punkteFuer(-570, 100, 'KAUFMAENNISCH'), -600)
  assert.equal(punkteFuer(-570, 100, 'ABRUNDEN'), -500)
})

test('Anzeige schneidet nachlaufende Nullen ab', () => {
  assert.equal(formatierePunkte(520), '5,2')
  assert.equal(formatierePunkte(2600), '26')
  assert.equal(formatierePunkte(104), '1,04')
  assert.equal(formatierePunkte(0), '0')
})

test('Eingabe mit Komma wird gelesen', () => {
  assert.equal(centsAusEingabe('145,50'), 14550)
  assert.equal(centsAusEingabe('145.50'), 14550)
  assert.equal(centsAusEingabe('145'), 14500)
  assert.equal(centsAusEingabe('0,05'), 5)
})

test('leere oder unsinnige Eingabe gibt null', () => {
  // null statt 0: das Personal soll den Buchen-Knopf nicht druecken
  // koennen, solange nichts Brauchbares im Feld steht.
  assert.equal(centsAusEingabe(''), null)
  assert.equal(centsAusEingabe('   '), null)
  assert.equal(centsAusEingabe('abc'), null)
  assert.equal(centsAusEingabe('-5'), null)
})

test('mehr als zwei Nachkommastellen werden abgeschnitten', () => {
  assert.equal(centsAusEingabe('1,239'), 123)
})
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `npm test`
Expected: FAIL, `Cannot find module './pointsOf.js'`

- [ ] **Step 3: `pointsOf.js` schreiben**

`src/lib/pointsOf.js`:

```js
/**
 * Umrechnung Einkaufsbetrag nach Punkten - dieselbe Rechnung wie PointsMath
 * im Backend.
 *
 * Doppelt gerechnet wird sie, damit der Scanner LIVE zeigen kann, was eine
 * Buchung ergibt, bevor das Personal bestaetigt. Ohne Vorschau merkt man
 * den Vertipper erst hinterher.
 *
 * Alle Punktwerte sind Hundertstel: 520 bedeutet 5,20 Punkte. Kein
 * Gleitkomma an keiner Stelle - sonst steht irgendwann 5,199999 auf dem
 * Bildschirm.
 */

export const MAX_AMOUNT_CENTS = 9_999_999

/**
 * @param {number} amountCents Betrag in Cent, Vorzeichen erlaubt
 * @param {number} pointsPerEuroX100 Punkte pro Euro, mal 100
 * @param {string} rounding 'GENAU' | 'ABRUNDEN' | 'KAUFMAENNISCH'
 * @returns {number} Punkte mal 100
 */
export function punkteFuer(amountCents, pointsPerEuroX100, rounding) {
  // Auf dem Betrag OHNE Vorzeichen rechnen und es am Ende zuruecksetzen:
  // Math.trunc schneidet Richtung Null ab, damit wuerde -5,70 kaufmaennisch
  // faelschlich auf -5 statt -6 runden und sich anders verhalten als die
  // Buchung, die es zuruecknimmt.
  const vorzeichen = amountCents < 0 ? -1 : 1
  const betrag = Math.abs(amountCents)

  // Zaehler traegt zwei Stellen mehr als das Ergebnis.
  const zaehler = betrag * pointsPerEuroX100

  let ergebnis
  if (rounding === 'ABRUNDEN') {
    ergebnis = Math.trunc(zaehler / 10000) * 100
  } else if (rounding === 'KAUFMAENNISCH') {
    ergebnis = Math.trunc((zaehler + 5000) / 10000) * 100
  } else {
    ergebnis = Math.trunc((zaehler + 50) / 100)
  }

  return vorzeichen * ergebnis
}

/** 520 wird "5,2", 2600 wird "26", 104 wird "1,04". Komma, weil die
 *  Oberflaeche deutsch und arabisch ist und beide es so setzen. */
export function formatierePunkte(pointsX100) {
  const ganz = Math.trunc(pointsX100 / 100)
  const rest = Math.abs(pointsX100 % 100)
  if (rest === 0) return String(ganz)
  const nachkomma = rest % 10 === 0
    ? String(rest / 10)
    : (rest < 10 ? '0' + rest : String(rest))
  return ganz + ',' + nachkomma
}

/**
 * Liest das Betragsfeld. Gibt null, wenn nichts Brauchbares drinsteht -
 * daran haengt, ob der Buchen-Knopf ueberhaupt gedrueckt werden kann.
 *
 * Komma und Punkt gelten beide: auf der Zahlentastatur eines iPhones liegt
 * je nach Sprache mal das eine, mal das andere.
 */
export function centsAusEingabe(text) {
  if (typeof text !== 'string') return null
  const sauber = text.trim().replace(',', '.')
  if (sauber === '') return null
  if (!/^\d+(\.\d{0,})?$/.test(sauber)) return null

  const [ganz, nach = ''] = sauber.split('.')
  // Auf zwei Stellen abschneiden, nicht runden: 1,239 Euro sind 1,23 Euro,
  // keine 1,24 - der Kassenbon rundet auch nicht nach oben.
  const cents = Number(ganz) * 100 + Number((nach + '00').slice(0, 2))
  if (!Number.isFinite(cents) || cents <= 0) return null
  return cents
}
```

- [ ] **Step 4: Test laufen lassen, grün bestätigen**

Run: `npm test`
Expected: PASS, alle Tests inklusive der bestehenden `progressOf`-Tests

- [ ] **Step 5: Commit**

```bash
git add src/lib/pointsOf.js src/lib/pointsOf.test.js
git commit -m "feat: Umrechnung Euro nach Punkten fuer die Live-Vorschau"
```

---

### Task 2: Texte in drei Sprachen

Vor den Oberflächen, damit dort kein Schlüssel fehlt. Ein fehlender Schlüssel fällt beim Build nicht auf — er steht dann roh in der Oberfläche.

**Files:**
- Modify: `src/i18n.js` (drei Blöcke: `de`, `en`, `ar`)

**Interfaces:**
- Produces: die unten gelisteten Schlüssel, aufgerufen als `t('schluessel')` bzw. `t('schluessel', { vars })`.

- [ ] **Step 1: Die deutschen Texte ergänzen**

In `src/i18n.js` im `de`-Block, hinter den bestehenden `cards_*`-Schlüsseln:

```js
    // Punktekarte - Anlegen und Katalog
    cards_type_stamp: 'Stempelkarte', cards_type_points: 'Punktekarte',
    cards_type_hint: 'Der Typ laesst sich spaeter nicht mehr aendern.',
    cards_rate: 'Kurs', cards_rate_dir_per_euro: 'Punkte pro Euro',
    cards_rate_dir_per_point: 'Euro pro Punkt',
    cards_rate_example: '{euro} Euro Einkauf ergibt {punkte} Punkte',
    cards_rounding: 'Rundung',
    cards_rounding_genau: 'Genau (zwei Nachkommastellen)',
    cards_rounding_abrunden: 'Auf ganze Punkte abrunden',
    cards_rounding_kaufmaennisch: 'Auf ganze Punkte runden',
    cards_catalog: 'Praemien', cards_catalog_hint: 'Wofuer der Kunde seine Punkte einloest.',
    cards_catalog_name: 'Praemie', cards_catalog_name_ph: 'z.B. Gratis-Kaffee',
    cards_catalog_cost: 'Punkte', cards_catalog_add: 'Praemie hinzufuegen',
    cards_catalog_empty: 'Noch keine Praemie. Ohne Praemie zeigt die Karte nur den Punktestand.',
    cards_catalog_max: 'Hoechstens 20 Praemien je Karte',
    cards_points_count: '{n} Punkte',

    // Punktekarte - Scanner
    scan_points_title: 'Punkte buchen', scan_points_balance: 'Punktestand',
    scan_points_amount: 'Rechnungsbetrag', scan_points_amount_ph: 'z.B. 14,50',
    scan_points_preview: '{betrag} Euro ergibt {punkte} Punkte',
    scan_points_book: 'Buchen', scan_points_booked: 'Gebucht',
    scan_points_next_goal: 'Naechste Praemie', scan_points_missing: 'noch {n}',
    scan_points_all_reached: 'Alle Praemien erreichbar',
    scan_points_no_goal: 'Noch keine Praemie hinterlegt',
    scan_points_redeem: 'Praemie einloesen', scan_points_redeem_done: 'Eingeloest',
    scan_points_correct: 'Korrigieren',
    scan_points_correct_hint: 'Betrag mit Minus abziehen, z.B. -14,50',
    scan_points_undo: 'Letzte Buchung zuruecknehmen',
    scan_points_undo_done: 'Zurueckgenommen',
    scan_points_last: 'Zuletzt: {text}',
    scan_points_confirm_big: '{betrag} Euro - stimmt das?',
    scan_points_backend_old: 'Der Server wird gerade aktualisiert. In ein paar Minuten nochmal versuchen.',
```

- [ ] **Step 2: Dieselben Schlüssel im `en`-Block**

```js
    // Points card - setup and catalog
    cards_type_stamp: 'Stamp card', cards_type_points: 'Points card',
    cards_type_hint: 'The type cannot be changed later.',
    cards_rate: 'Rate', cards_rate_dir_per_euro: 'points per euro',
    cards_rate_dir_per_point: 'euros per point',
    cards_rate_example: '{euro} euro purchase gives {punkte} points',
    cards_rounding: 'Rounding',
    cards_rounding_genau: 'Exact (two decimals)',
    cards_rounding_abrunden: 'Round down to whole points',
    cards_rounding_kaufmaennisch: 'Round to whole points',
    cards_catalog: 'Rewards', cards_catalog_hint: 'What customers spend their points on.',
    cards_catalog_name: 'Reward', cards_catalog_name_ph: 'e.g. Free coffee',
    cards_catalog_cost: 'Points', cards_catalog_add: 'Add reward',
    cards_catalog_empty: 'No rewards yet. Without one the card shows only the balance.',
    cards_catalog_max: 'At most 20 rewards per card',
    cards_points_count: '{n} points',

    // Points card - scanner
    scan_points_title: 'Add points', scan_points_balance: 'Balance',
    scan_points_amount: 'Bill amount', scan_points_amount_ph: 'e.g. 14.50',
    scan_points_preview: '{betrag} euro gives {punkte} points',
    scan_points_book: 'Add', scan_points_booked: 'Added',
    scan_points_next_goal: 'Next reward', scan_points_missing: '{n} to go',
    scan_points_all_reached: 'All rewards within reach',
    scan_points_no_goal: 'No reward set up yet',
    scan_points_redeem: 'Redeem reward', scan_points_redeem_done: 'Redeemed',
    scan_points_correct: 'Correct',
    scan_points_correct_hint: 'Use a minus to subtract, e.g. -14.50',
    scan_points_undo: 'Undo last entry',
    scan_points_undo_done: 'Undone',
    scan_points_last: 'Last: {text}',
    scan_points_confirm_big: '{betrag} euro - is that right?',
    scan_points_backend_old: 'The server is updating. Try again in a few minutes.',
```

- [ ] **Step 3: Dieselben Schlüssel im `ar`-Block**

Jede Zeile bekommt `// TODO ar-review`, wie im Arabisch-Spec vereinbart. Vor echtem Go-Live liest ein Muttersprachler gegen.

```js
    // Points card - TODO ar-review (Muttersprachler gegenlesen)
    cards_type_stamp: 'بطاقة أختام', cards_type_points: 'بطاقة نقاط',
    cards_type_hint: 'لا يمكن تغيير النوع لاحقاً.',
    cards_rate: 'المعدل', cards_rate_dir_per_euro: 'نقاط لكل يورو',
    cards_rate_dir_per_point: 'يورو لكل نقطة',
    cards_rate_example: 'شراء بقيمة {euro} يورو يمنح {punkte} نقطة',
    cards_rounding: 'التقريب',
    cards_rounding_genau: 'بالضبط (خانتان عشريتان)',
    cards_rounding_abrunden: 'تقريب لأسفل إلى نقاط كاملة',
    cards_rounding_kaufmaennisch: 'تقريب إلى نقاط كاملة',
    cards_catalog: 'المكافآت', cards_catalog_hint: 'ما يستبدل به العميل نقاطه.',
    cards_catalog_name: 'المكافأة', cards_catalog_name_ph: 'مثال: قهوة مجانية',
    cards_catalog_cost: 'النقاط', cards_catalog_add: 'إضافة مكافأة',
    cards_catalog_empty: 'لا توجد مكافآت بعد. بدونها تعرض البطاقة الرصيد فقط.',
    cards_catalog_max: 'حد أقصى 20 مكافأة لكل بطاقة',
    cards_points_count: '{n} نقطة',

    scan_points_title: 'إضافة نقاط', scan_points_balance: 'الرصيد',
    scan_points_amount: 'قيمة الفاتورة', scan_points_amount_ph: 'مثال: 14,50',
    scan_points_preview: '{betrag} يورو تمنح {punkte} نقطة',
    scan_points_book: 'تسجيل', scan_points_booked: 'تم التسجيل',
    scan_points_next_goal: 'المكافأة التالية', scan_points_missing: 'باقي {n}',
    scan_points_all_reached: 'كل المكافآت في المتناول',
    scan_points_no_goal: 'لا توجد مكافأة بعد',
    scan_points_redeem: 'استبدال مكافأة', scan_points_redeem_done: 'تم الاستبدال',
    scan_points_correct: 'تصحيح',
    scan_points_correct_hint: 'استخدم علامة ناقص للخصم، مثال: ‎-14,50',
    scan_points_undo: 'التراجع عن آخر عملية',
    scan_points_undo_done: 'تم التراجع',
    scan_points_last: 'الأخيرة: {text}',
    scan_points_confirm_big: '{betrag} يورو - هل هذا صحيح؟',
    scan_points_backend_old: 'يتم تحديث الخادم. حاول مرة أخرى بعد دقائق.',
```

- [ ] **Step 4: Prüfen, dass kein Schlüssel in einer Sprache fehlt**

```bash
node -e "
import('./src/i18n.js').then(({translations}) => {
  const de = Object.keys(translations.de)
  for (const lang of ['en','ar']) {
    const fehlt = de.filter(k => !(k in translations[lang]))
    console.log(lang, fehlt.length ? 'FEHLT: ' + fehlt.join(', ') : 'vollstaendig')
  }
})"
```

Expected: `en vollstaendig` und `ar vollstaendig`

- [ ] **Step 5: Build und Commit**

```bash
npm run build
git add src/i18n.js
git commit -m "feat: Texte fuer die Punktekarte in de, en und ar"
```

---

### Task 3: Kartenvorschau herausziehen

Reiner Umzug, kein Verhaltenswechsel. `Karten.jsx` hat 860 Zeilen und trägt `ApplePreview` und `GooglePreview` mit sich; beide müssen gleich die Punkte-Variante zeichnen. Erst umziehen, dann erweitern — sonst steht die Datei am Ende bei 1200 Zeilen.

**Files:**
- Create: `src/components/CardPreview.jsx`
- Modify: `src/pages/Karten.jsx` (Zeilen 116-260, die beiden Vorschau-Komponenten)

**Interfaces:**
- Produces: `export function ApplePreview({ design, stamps, threshold, rewardText, cardName, t })` und `export function GooglePreview(...)` — Signaturen unverändert übernommen.

- [ ] **Step 1: Die beiden Komponenten in die neue Datei verschieben**

`src/components/CardPreview.jsx` anlegen, `ApplePreview` und `GooglePreview` aus `Karten.jsx` unverändert hineinkopieren, dazu die Stile, die nur sie benutzen. Kopf der Datei:

```jsx
/**
 * Wallet-Vorschauen fuer die Karten-Verwaltung.
 *
 * Lagen in Karten.jsx, die damit auf 860 Zeilen stand. Beide muessen jetzt
 * zusaetzlich die Punkte-Variante zeichnen - deshalb vorher hier heraus,
 * sonst waere die Seite auf ueber 1200 Zeilen gewachsen und niemand faende
 * sich mehr zurecht.
 *
 * Dieser Schritt ist ein reiner Umzug: an der Darstellung aendert sich
 * nichts.
 */
import Icon from './Icon'
```

- [ ] **Step 2: In `Karten.jsx` importieren statt definieren**

Die beiden Funktionsdefinitionen entfernen und oben ergänzen:

```jsx
import { ApplePreview, GooglePreview } from '../components/CardPreview'
```

- [ ] **Step 3: Build laufen lassen**

Run: `npm run build`
Expected: erfolgreich, keine Warnung über unbenutzte Bezeichner

- [ ] **Step 4: Von Hand prüfen**

Run: `npm run dev`, Karten-Seite öffnen, Karte anlegen wählen. Beide Vorschauen müssen aussehen wie vorher — dies ist ein Umzug, kein Umbau.

- [ ] **Step 5: Commit**

```bash
git add src/components/CardPreview.jsx src/pages/Karten.jsx
git commit -m "refactor: Wallet-Vorschauen aus Karten.jsx herausgezogen"
```

---

### Task 4: Punktekarte anlegen und Katalog pflegen

**Files:**
- Create: `src/components/PointsSettings.jsx`
- Create: `src/components/RewardCatalog.jsx`
- Modify: `src/pages/Karten.jsx` (Typ-Umschalter, `createCard`, Bearbeiten-Ansicht)
- Modify: `src/components/CardPreview.jsx` (Punkte-Variante zeichnen)

**Interfaces:**
- Consumes: `punkteFuer`, `formatierePunkte` aus Task 1; die i18n-Schlüssel aus Task 2.
- Produces:
  - `<PointsSettings value={{pointsPerEuroX100, pointsRounding}} onChange={fn} t={t} />`
  - `<RewardCatalog rewards={[]} onAdd={fn} onRemove={fn} t={t} />`

- [ ] **Step 1: `PointsSettings.jsx` anlegen**

```jsx
import { punkteFuer, formatierePunkte } from '../lib/pointsOf'

/**
 * Kurs und Rundung einer Punktekarte.
 *
 * Nach aussen gibt es nur EINE Zahl: pointsPerEuroX100 (Punkte pro Euro,
 * mal 100). Hier drin darf der Laden waehlen, wie er es ausspricht -
 * "5 Punkte pro Euro" und "1 Punkt pro 5 Euro" meinen dasselbe Feld, nur
 * einmal als Kehrwert. Das erspart der Datenbank ein zweites Feld und
 * jedem Rechenweg eine Fallunterscheidung.
 */
export default function PointsSettings({ value, onChange, t }) {
  const { pointsPerEuroX100, pointsRounding } = value

  // Welche Sprechweise steht gerade im Feld? Unter 100 Punkten pro Euro
  // liest sich "1 Punkt pro X Euro" natuerlicher.
  const proEuro = pointsPerEuroX100 >= 100
  const angezeigt = proEuro
    ? pointsPerEuroX100 / 100
    : Math.round(10000 / pointsPerEuroX100) / 100

  function setzeRichtung(neuProEuro) {
    // Beim Umschalten den Kehrwert bilden, damit die Zahl im Feld stehen
    // bleibt und nicht ploetzlich etwas anderes bedeutet.
    onChange({
      ...value,
      pointsPerEuroX100: neuProEuro
        ? Math.round(angezeigt * 100)
        : Math.round(10000 / angezeigt),
    })
  }

  function setzeZahl(text) {
    const zahl = parseFloat(String(text).replace(',', '.'))
    if (!Number.isFinite(zahl) || zahl <= 0) return
    onChange({
      ...value,
      pointsPerEuroX100: proEuro
        ? Math.round(zahl * 100)
        : Math.round(10000 / zahl),
    })
  }

  // Beispielrechnung mit 10 Euro, damit der Laden sofort sieht, was er
  // eingestellt hat. Genau die Zahl, die spaeter im Scanner steht.
  const beispiel = formatierePunkte(
    punkteFuer(1000, pointsPerEuroX100, pointsRounding))

  return (
    <div style={s.block}>
      <label style={s.label}>{t('cards_rate')}</label>
      <div style={s.row}>
        <input
          style={s.zahl}
          type="text"
          inputMode="decimal"
          value={angezeigt}
          onChange={e => setzeZahl(e.target.value)}
        />
        <select
          style={s.select}
          value={proEuro ? 'per_euro' : 'per_point'}
          onChange={e => setzeRichtung(e.target.value === 'per_euro')}
        >
          <option value="per_euro">{t('cards_rate_dir_per_euro')}</option>
          <option value="per_point">{t('cards_rate_dir_per_point')}</option>
        </select>
      </div>
      <div style={s.beispiel}>
        {t('cards_rate_example', { euro: '10', punkte: beispiel })}
      </div>

      <label style={s.label}>{t('cards_rounding')}</label>
      <select
        style={s.select}
        value={pointsRounding}
        onChange={e => onChange({ ...value, pointsRounding: e.target.value })}
      >
        <option value="GENAU">{t('cards_rounding_genau')}</option>
        <option value="ABRUNDEN">{t('cards_rounding_abrunden')}</option>
        <option value="KAUFMAENNISCH">{t('cards_rounding_kaufmaennisch')}</option>
      </select>
    </div>
  )
}

const s = {
  block: { marginBottom: '20px' },
  label: { display: 'block', fontSize: '13px', fontWeight: '600', color: '#555', marginBottom: '6px' },
  row: { display: 'flex', gap: '8px', marginBottom: '6px' },
  zahl: { width: '110px', padding: '12px', border: '1.5px solid #e0e0e0', borderRadius: '10px', fontSize: '15px' },
  select: { flex: 1, padding: '12px', border: '1.5px solid #e0e0e0', borderRadius: '10px', fontSize: '15px', background: 'white' },
  beispiel: { fontSize: '13px', color: '#888', marginBottom: '16px' },
}
```

- [ ] **Step 2: `RewardCatalog.jsx` anlegen**

```jsx
import { useState } from 'react'
import { formatierePunkte } from '../lib/pointsOf'
import Icon from './Icon'

const MAX_REWARDS = 20

/**
 * Der Praemien-Katalog einer Punktekarte.
 *
 * Preise liegen wie jeder Punktwert in Hundertsteln. Eingetippt wird
 * allerdings in ganzen Punkten - eine Praemie fuer 2,5 Punkte hat noch
 * niemand gebraucht, und die Eingabe bliebe fehleranfaellig.
 */
export default function RewardCatalog({ rewards, onAdd, onRemove, t }) {
  const [name, setName] = useState('')
  const [kosten, setKosten] = useState('')

  function hinzufuegen() {
    const punkte = parseInt(kosten, 10)
    if (!name.trim() || !Number.isFinite(punkte) || punkte < 1) return
    onAdd(name.trim(), punkte * 100)
    setName('')
    setKosten('')
  }

  const voll = rewards.length >= MAX_REWARDS

  return (
    <div style={s.block}>
      <label style={s.label}>{t('cards_catalog')}</label>
      <div style={s.hint}>{t('cards_catalog_hint')}</div>

      {rewards.length === 0 && (
        <div style={s.leer}>{t('cards_catalog_empty')}</div>
      )}

      {rewards.map(r => (
        <div key={r.id ?? r.name} style={s.zeile}>
          <span style={s.zeileName}>{r.name}</span>
          <span style={s.zeileKosten}>
            {t('cards_points_count', { n: formatierePunkte(r.costPointsX100) })}
          </span>
          <button style={s.weg} onClick={() => onRemove(r)} title={t('common_remove')}>
            <Icon name="x" size={16} strokeWidth={2.4} />
          </button>
        </div>
      ))}

      {voll ? (
        <div style={s.hint}>{t('cards_catalog_max')}</div>
      ) : (
        <div style={s.row}>
          <input
            style={s.name}
            type="text"
            maxLength={40}
            placeholder={t('cards_catalog_name_ph')}
            value={name}
            onChange={e => setName(e.target.value)}
          />
          <input
            style={s.kosten}
            type="text"
            inputMode="numeric"
            placeholder={t('cards_catalog_cost')}
            value={kosten}
            onChange={e => setKosten(e.target.value)}
          />
          <button style={s.add} onClick={hinzufuegen}>
            <Icon name="check" size={16} strokeWidth={2.4} />
          </button>
        </div>
      )}
    </div>
  )
}

const s = {
  block: { marginBottom: '20px' },
  label: { display: 'block', fontSize: '13px', fontWeight: '600', color: '#555', marginBottom: '4px' },
  hint: { fontSize: '12px', color: '#999', marginBottom: '10px' },
  leer: { fontSize: '13px', color: '#999', background: '#f8f8f8', borderRadius: '10px', padding: '12px', marginBottom: '10px' },
  zeile: { display: 'flex', alignItems: 'center', gap: '8px', padding: '10px 12px', background: '#f8f8f8', borderRadius: '10px', marginBottom: '6px' },
  zeileName: { flex: 1, fontSize: '14px', fontWeight: '600', color: '#1a1a1a' },
  zeileKosten: { fontSize: '13px', color: '#666' },
  weg: { background: 'none', border: 'none', color: '#c0392b', cursor: 'pointer', display: 'flex', padding: '4px' },
  row: { display: 'flex', gap: '8px' },
  name: { flex: 1, padding: '12px', border: '1.5px solid #e0e0e0', borderRadius: '10px', fontSize: '15px' },
  kosten: { width: '90px', padding: '12px', border: '1.5px solid #e0e0e0', borderRadius: '10px', fontSize: '15px' },
  add: { padding: '12px 16px', background: '#3C3489', color: 'white', border: 'none', borderRadius: '10px', cursor: 'pointer', display: 'flex' },
}
```

- [ ] **Step 3: Typ-Umschalter in `Karten.jsx` einbauen**

Zustand ergänzen, neben `form`:

```jsx
  const [cardType, setCardType] = useState('STAMP')
  const [pointsForm, setPointsForm] = useState({
    pointsPerEuroX100: 100, pointsRounding: 'GENAU',
  })
  // Praemien werden beim Anlegen lokal gesammelt und erst nach dem
  // Erstellen der Karte hochgeladen - genau wie das Stempel-Bild, das auch
  // erst eine Karten-ID braucht.
  const [pendingRewards, setPendingRewards] = useState([])
```

Im Anlege-Formular ganz oben, vor den Textfeldern:

```jsx
              <div style={s.typeRow}>
                {['STAMP', 'POINTS'].map(typ => (
                  <button
                    key={typ}
                    style={{
                      ...s.typeBtn,
                      background: cardType === typ ? '#3C3489' : '#f0f0f0',
                      color: cardType === typ ? 'white' : '#333',
                    }}
                    onClick={() => setCardType(typ)}
                  >
                    {t(typ === 'STAMP' ? 'cards_type_stamp' : 'cards_type_points')}
                  </button>
                ))}
              </div>
              <div style={s.typeHint}>{t('cards_type_hint')}</div>
```

Die Felder „Belohnung" und „Stempel bis Belohnung" nur bei `cardType === 'STAMP'` rendern, und stattdessen bei `POINTS`:

```jsx
              {cardType === 'POINTS' && (
                <>
                  <PointsSettings value={pointsForm} onChange={setPointsForm} t={t} />
                  <RewardCatalog
                    rewards={pendingRewards}
                    onAdd={(name, costPointsX100) =>
                      setPendingRewards(r => [...r, { name, costPointsX100 }])}
                    onRemove={(weg) =>
                      setPendingRewards(r => r.filter(x => x !== weg))}
                    t={t}
                  />
                </>
              )}
```

Stile ergänzen:

```jsx
  typeRow: { display: 'flex', gap: '8px', marginBottom: '6px' },
  typeBtn: { flex: 1, padding: '12px', borderRadius: '10px', border: 'none', fontSize: '15px', fontWeight: '700', cursor: 'pointer' },
  typeHint: { fontSize: '12px', color: '#999', marginBottom: '16px' },
```

- [ ] **Step 4: `createCard` für beide Typen**

```jsx
  async function createCard() {
    // Bei einer Punktekarte gibt es keinen Belohnungstext - der Katalog
    // ersetzt ihn. Die Pflichtpruefung muss das wissen, sonst laesst sich
    // keine Punktekarte anlegen.
    const pflichtFehlt = cardType === 'POINTS'
      ? !form.name
      : !form.name || !form.rewardText
    if (pflichtFehlt) return alert(t('cards_err_required'))

    setLoading(true)
    try {
      const { stampIconUrl, ...designToSave } = design

      let res
      if (cardType === 'POINTS') {
        res = await api.post('/api/shop/cards/points', {
          name: form.name,
          description: form.description,
          pointsPerEuroX100: pointsForm.pointsPerEuroX100,
          pointsRounding: pointsForm.pointsRounding,
          colorBackground: designToSave.colorBackground,
          colorForeground: designToSave.colorForeground,
          colorLabel: designToSave.colorLabel,
          logoUrl: designToSave.logoUrl,
          heroImageUrl: designToSave.heroImageUrl,
        })
        // Praemien an die frisch erstellte Karte haengen, der Reihe nach -
        // die Sortierung ergibt sich aus der Reihenfolge des Anlegens.
        for (const r of pendingRewards) {
          await api.post(`/api/shop/cards/${res.data.id}/rewards`, {
            name: r.name, costPointsX100: r.costPointsX100,
          })
        }
      } else {
        res = await api.post('/api/shop/cards', {
          ...form,
          description: form.description,
          rewardThreshold: parseInt(form.rewardThreshold),
          ...designToSave,
        })
        // ... bestehender Stempel-Bild-Upload unveraendert ...
      }

      setMode('list')
      setForm({name:'',description:'',rewardThreshold:10,rewardText:''})
      setDesign({...DEFAULT_DESIGN})
      setPendingStampFile(null)
      setPendingRewards([])
      setCardType('STAMP')
      loadCards()
    } catch (e) {
      // 404 heisst hier fast immer: Backend-Deploy laeuft noch. Das ist
      // eine andere Auskunft als "Anlegen fehlgeschlagen" und erspart die
      // Suche nach einem Fehler, den es nicht gibt.
      alert(e.response?.status === 404
        ? t('scan_points_backend_old')
        : (e.response?.data?.error || t('cards_err_create')))
    }
    finally { setLoading(false) }
  }
```

- [ ] **Step 5: Katalog in der Bearbeiten-Ansicht**

In der Bearbeiten-Ansicht bei `editCard.type === 'POINTS'` den Katalog laden und pflegen:

```jsx
  const [editRewards, setEditRewards] = useState([])

  async function ladeRewards(cardId) {
    const r = await api.get(`/api/shop/cards/${cardId}/rewards`)
    setEditRewards(r.data)
  }

  async function rewardHinzufuegen(name, costPointsX100) {
    await api.post(`/api/shop/cards/${editCard.id}/rewards`, { name, costPointsX100 })
    ladeRewards(editCard.id)
  }

  async function rewardEntfernen(reward) {
    await api.delete(`/api/shop/cards/${editCard.id}/rewards/${reward.id}`)
    ladeRewards(editCard.id)
  }
```

In `openEdit` ergänzen: `if (card.type === 'POINTS') ladeRewards(card.id)`.

Die Felder „Belohnung" und „Stempel-Anzahl" in der Bearbeiten-Ansicht bei Punktekarten durch `<RewardCatalog rewards={editRewards} onAdd={rewardHinzufuegen} onRemove={rewardEntfernen} t={t} />` ersetzen. Der Typ selbst wird nicht angeboten.

- [ ] **Step 6: Kartenliste: Abzeichen nach Typ**

In der Listenansicht das Abzeichen `cards_stamp_count` bei Punktekarten ersetzen:

```jsx
                      <div style={{...s.badge, background:card.colorBackground||'#3C3489', color:card.colorForeground||'#fff'}}>
                        {(card.type ?? 'STAMP') === 'POINTS'
                          ? t('cards_type_points')
                          : t('cards_stamp_count', { n: card.rewardThreshold })}
                      </div>
```

`card.type ?? 'STAMP'` statt `card.type`: ein älteres Backend liefert das Feld noch nicht.

- [ ] **Step 7: Punkte-Variante in `CardPreview.jsx`**

Beide Vorschauen bekommen einen Zweig für Punkte. Statt des Stempelrasters beziehungsweise `7/10` zeigen sie den Beispielstand und das nächste Ziel:

```jsx
  // Bei Punkten gibt es kein Raster zu zeichnen - das Stempel-Design
  // (Icon, Raster, Leerstil) hat hier nichts zu tun. Gezeigt wird, was auf
  // dem echten Pass steht: Stand oben, naechstes Ziel gross.
  if (cardType === 'POINTS') {
    const ziel = rewards.find(r => r.costPointsX100 > previewPointsX100) ?? rewards.at(-1)
    const fehlend = ziel ? Math.max(0, ziel.costPointsX100 - previewPointsX100) : 0
    // ... Felder rendern: PUNKTE / formatierePunkte(previewPointsX100)
    //     NAECHSTE PRAEMIE / ziel?.name, "noch " + formatierePunkte(fehlend)
  }
```

`cardType`, `rewards` und `previewPointsX100` als zusätzliche Eigenschaften durchreichen; bei Stempelkarten bleibt alles unverändert.

- [ ] **Step 8: Build und Handprobe**

Run: `npm run build`, dann `npm run dev`.

Prüfen: Punktekarte anlegen, Kurs auf „1 Punkt pro 5 Euro" stellen, Beispielzeile muss `10 Euro Einkauf ergibt 2 Punkte` zeigen. Zwei Prämien hinzufügen, Karte anlegen, in der Liste erscheint das Abzeichen „Punktekarte". Bestehende Stempelkarten müssen unverändert aussehen.

- [ ] **Step 9: Commit**

```bash
git add src/components/PointsSettings.jsx src/components/RewardCatalog.jsx \
        src/components/CardPreview.jsx src/pages/Karten.jsx
git commit -m "feat: Punktekarte anlegen und Praemien-Katalog pflegen"
```

---

### Task 5: Scanner-Weiche und Punkte buchen

**Files:**
- Modify: `src/pages/Scanner.jsx`

**Interfaces:**
- Consumes: `punkteFuer`, `formatierePunkte`, `centsAusEingabe` aus Task 1; die i18n-Schlüssel aus Task 2.
- Produces: den Zustand `scanState` (Antwort von `/api/scan/state`), auf den Task 6 aufbaut.

- [ ] **Step 1: Nach dem Scan den Zustand holen**

`handleQrScanned` erweitern:

```jsx
  const [scanState, setScanState] = useState(null)
  const [betrag, setBetrag] = useState('')
  const [pointsError, setPointsError] = useState(null)

  async function handleQrScanned(payload) {
    setQrInput('')
    await stopCamera()
    setPendingScan(payload)
    setSelectedCount(1)
    setBetrag('')
    setPointsError(null)
    setScanState(null)

    // Welcher Kartentyp? Steht nicht im QR, sondern hinter der cardId.
    try {
      const token = localStorage.getItem('staffToken')
      const res = await fetch(`${API_URL}/api/scan/state`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Staff-Token': token },
        body: JSON.stringify({ qrPayload: payload }),
      })
      if (res.ok) {
        setScanState(await res.json())
      }
      // Kein ok: der Server kennt die Route noch nicht (Deploy laeuft) oder
      // die Karte ist unbekannt. Beides faellt unten auf die Stempelmaske
      // zurueck, und der eigentliche Fehler kommt beim Buchen mit einer
      // brauchbaren Meldung.
    } catch {
      // Netzfehler: ebenfalls Stempelmaske. Kein Abbruch, das Personal
      // steht am Kunden.
    }
  }
```

- [ ] **Step 2: Die Weiche im Bestätigen-Dialog**

Im Block `{pendingScan && !result && (...)}` als Erstes entscheiden:

```jsx
          const istPunkte = (scanState?.type ?? 'STAMP') === 'POINTS'
```

Bei `!istPunkte` bleibt der bestehende Inhalt Zeile für Zeile stehen — Anzahl-Wähler, Bestätigen, Reset, Abbrechen. Das ist der Weg, der in echten Läden läuft.

- [ ] **Step 3: Die Punktemaske**

```jsx
            {istPunkte && (
              <>
                <h2 style={styles.popupTitle}>{t('scan_points_title')}</h2>
                <div style={styles.popupSubtitle}>{scanState.customerName}</div>

                <div style={styles.standBox}>
                  <div style={styles.standLabel}>{t('scan_points_balance')}</div>
                  <div style={styles.standWert}>
                    {formatierePunkte(scanState.pointsX100)}
                  </div>
                  {scanState.ziel ? (
                    <div style={styles.zielZeile}>
                      {t('scan_points_next_goal')}: {scanState.ziel.name}
                      {' - '}
                      {t('scan_points_missing', {
                        n: formatierePunkte(scanState.fehlendX100),
                      })}
                    </div>
                  ) : (
                    <div style={styles.zielZeile}>{t('scan_points_no_goal')}</div>
                  )}
                </div>

                <label style={styles.feldLabel}>{t('scan_points_amount')}</label>
                <input
                  style={styles.betragFeld}
                  type="text"
                  inputMode="decimal"
                  placeholder={t('scan_points_amount_ph')}
                  value={betrag}
                  onChange={e => setBetrag(e.target.value)}
                  autoFocus
                />

                {/* Live, was die Buchung ergibt. Der Grund, warum die
                    Rechnung auch im Frontend liegt: das Personal soll das
                    Ergebnis sehen, BEVOR es bestaetigt - nicht danach. */}
                {cents !== null && (
                  <div style={styles.vorschau}>
                    {t('scan_points_preview', {
                      betrag: (cents / 100).toFixed(2).replace('.', ','),
                      punkte: formatierePunkte(punkteFuer(
                        cents, scanState.pointsPerEuroX100, scanState.pointsRounding)),
                    })}
                  </div>
                )}

                {pointsError && <div style={styles.fehlerBanner}>{pointsError}</div>}

                <button
                  style={{ ...styles.confirmBtn, opacity: cents === null ? 0.5 : 1 }}
                  onClick={buchen}
                  disabled={loading || cents === null}
                >
                  {loading ? t('scan_processing') : t('scan_points_book')}
                </button>
                <button style={styles.cancelBtn} onClick={cancelScan}>
                  {t('scan_cancel')}
                </button>
              </>
            )}
```

Oberhalb des Rückgabeblocks:

```jsx
  const cents = centsAusEingabe(betrag)
```

- [ ] **Step 4: `buchen` schreiben**

```jsx
  /** Ueber 500 Euro wird nachgefragt. Das ist die billigste Abwehr gegen
   *  den Vertipper, wegen dem es die Korrektur ueberhaupt gibt: 1450 statt
   *  145 ist schneller getippt, als man denkt. */
  const NACHFRAGE_AB_CENTS = 50_000

  async function buchen() {
    if (cents === null) return
    if (cents >= NACHFRAGE_AB_CENTS) {
      const text = t('scan_points_confirm_big', {
        betrag: (cents / 100).toFixed(2).replace('.', ','),
      })
      if (!confirm(text)) return
    }
    await punkteAufruf('/api/points/earn', { amountCents: cents })
  }

  /**
   * Gemeinsamer Weg fuer buchen, einloesen, korrigieren und zuruecknehmen.
   * Alle vier schicken denselben QR mit, bekommen dieselbe Antwort und
   * enden im selben Ergebnisfenster.
   */
  async function punkteAufruf(pfad, rumpf) {
    setLoading(true)
    setPointsError(null)
    try {
      const token = localStorage.getItem('staffToken')
      const res = await fetch(`${API_URL}${pfad}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Staff-Token': token },
        body: JSON.stringify({ qrPayload: pendingScan, ...rumpf }),
      })
      const data = await res.json().catch(() => ({}))
      if (res.ok) {
        setResult({ success: true, points: true, data })
        setPendingScan(null)
      } else if (res.status === 404) {
        // Meistens kein Fehler im Panel, sondern ein laufender Deploy.
        setPointsError(t('scan_points_backend_old'))
      } else {
        setPointsError(data.error || t('scan_server_error'))
      }
    } catch {
      setPointsError(t('scan_server_error'))
    } finally {
      setLoading(false)
    }
  }
```

- [ ] **Step 5: Ergebnisfenster für Punkte**

Im `{result && ...}`-Block vor der bestehenden Stempel-Auswertung:

```jsx
          if (result.points) {
            const d = result.data
            return (
              <div style={{ ...styles.resultBox, background: '#F0FFF4', borderColor: '#2C5F2E' }}>
                <div style={{ ...styles.resultIcon, display: 'flex', justifyContent: 'center', color: '#2C5F2E' }}>
                  <Icon name="check" size={34} strokeWidth={2.4} />
                </div>
                <div style={styles.resultMessage}>
                  {t('scan_points_booked')}: {d.pointsText} {t('cards_catalog_cost')}
                </div>
                {d.zielName && (
                  <div style={styles.resultStamps}>
                    {t('scan_points_next_goal')}: {d.zielName}
                    {' - '}{t('scan_points_missing', { n: d.fehlendText })}
                  </div>
                )}
                <button style={{ ...styles.btnNext, background: '#3C3489', color: '#fff' }}
                        onClick={nextCustomer}>{t('scan_next')}</button>
              </div>
            )
          }
```

In `nextCustomer` ergänzen: `setScanState(null); setBetrag(''); setPointsError(null)`.

- [ ] **Step 6: Stile ergänzen**

```jsx
  standBox: { background: '#f8f8ff', borderRadius: '14px', padding: '16px', marginBottom: '16px' },
  standLabel: { fontSize: '12px', fontWeight: '600', color: '#888', textTransform: 'uppercase', letterSpacing: '0.5px' },
  standWert: { fontSize: '36px', fontWeight: '900', color: '#3C3489', lineHeight: 1.1, margin: '4px 0' },
  zielZeile: { fontSize: '13px', color: '#666' },
  feldLabel: { display: 'block', fontSize: '13px', fontWeight: '600', color: '#555', marginBottom: '6px', textAlign: 'start' },
  betragFeld: { width: '100%', padding: '16px', fontSize: '22px', fontWeight: '700', textAlign: 'center', border: '1.5px solid #e0e0e0', borderRadius: '12px', marginBottom: '8px', boxSizing: 'border-box' },
  vorschau: { fontSize: '14px', fontWeight: '600', color: '#2C5F2E', background: '#F0FFF4', borderRadius: '10px', padding: '10px', marginBottom: '14px' },
  fehlerBanner: { fontSize: '13px', fontWeight: '600', color: '#c0392b', background: '#fff0f0', border: '1.5px solid #f5c6cb', borderRadius: '10px', padding: '10px', marginBottom: '12px' },
```

`textAlign: 'start'` statt `'left'`: auf Arabisch läuft die Oberfläche von rechts nach links.

- [ ] **Step 7: Build und Handprobe**

Run: `npm run build`, dann gegen ein laufendes Backend `npm run dev`.

Prüfen: eine Stempelkarte scannen — der Anzahl-Wähler muss erscheinen, unverändert. Eine Punktekarte scannen — Betragsfeld mit Live-Vorschau. `14,50` eintippen, die Vorschau muss `14,50 Euro ergibt 14,5 Punkte` zeigen (bei 1 Euro = 1 Punkt). `600` eintippen und buchen — die Rückfrage muss kommen.

- [ ] **Step 8: Commit**

```bash
git add src/pages/Scanner.jsx
git commit -m "feat: Scanner erkennt Punktekarten und bucht Einkaeufe"
```

---

### Task 6: Einlösen, korrigieren, zurücknehmen

**Files:**
- Modify: `src/pages/Scanner.jsx`

**Interfaces:**
- Consumes: `punkteAufruf`, `scanState` aus Task 5.

- [ ] **Step 1: Katalog zum Einlösen**

Unter dem Buchen-Knopf in der Punktemaske:

```jsx
                {scanState.katalog?.length > 0 && (
                  <div style={styles.katalog}>
                    <div style={styles.katalogTitel}>{t('scan_points_redeem')}</div>
                    {scanState.katalog.map(r => (
                      <button
                        key={r.id}
                        style={{
                          ...styles.praemie,
                          opacity: r.bezahlbar ? 1 : 0.45,
                          cursor: r.bezahlbar ? 'pointer' : 'default',
                        }}
                        disabled={!r.bezahlbar || loading}
                        onClick={() => punkteAufruf('/api/points/redeem', { rewardId: r.id })}
                      >
                        <span style={styles.praemieName}>{r.name}</span>
                        <span style={styles.praemieKosten}>
                          {r.bezahlbar
                            ? r.costText
                            : t('scan_points_missing', { n: formatierePunkte(r.fehlendX100) })}
                        </span>
                      </button>
                    ))}
                  </div>
                )}
```

Zu teure Prämien sind ausgegraut **und** gesperrt. Beides: das Ausgrauen allein hindert niemanden am Antippen, das Sperren allein erklärt nicht, warum nichts passiert.

- [ ] **Step 2: Korrektur**

```jsx
  const [korrekturOffen, setKorrekturOffen] = useState(false)
  const [korrekturBetrag, setKorrekturBetrag] = useState('')

  async function korrigieren() {
    // Vorzeichen selbst lesen: centsAusEingabe weist negative Eingaben ab,
    // weil beim normalen Buchen nichts Negatives gemeint sein kann.
    const text = korrekturBetrag.trim()
    const negativ = text.startsWith('-')
    const cents = centsAusEingabe(negativ ? text.slice(1) : text)
    if (cents === null) return
    await punkteAufruf('/api/points/correct',
      { amountCents: negativ ? -cents : cents })
    setKorrekturOffen(false)
    setKorrekturBetrag('')
  }
```

Oberfläche, unter dem Katalog:

```jsx
                {!korrekturOffen ? (
                  <button style={styles.nebenKnopf} onClick={() => setKorrekturOffen(true)}>
                    {t('scan_points_correct')}
                  </button>
                ) : (
                  <div style={styles.korrekturBox}>
                    <div style={styles.hinweis}>{t('scan_points_correct_hint')}</div>
                    <input
                      style={styles.betragFeld}
                      type="text"
                      inputMode="text"
                      value={korrekturBetrag}
                      onChange={e => setKorrekturBetrag(e.target.value)}
                      autoFocus
                    />
                    <button style={styles.confirmBtn} onClick={korrigieren} disabled={loading}>
                      {t('scan_points_correct')}
                    </button>
                  </div>
                )}
```

`inputMode="text"` statt `"decimal"`: die Dezimaltastatur mancher Geräte hat kein Minus.

- [ ] **Step 3: Letzte Buchung zurücknehmen**

```jsx
                {/* Der Server liefert hier bereits nur eine Buchung, die noch
                    nicht zurueckgenommen ist und selbst keine Gegenbuchung
                    ist - deshalb genuegt die Pruefung auf Vorhandensein. */}
                {scanState.letzteBuchung && (
                  <div style={styles.letzteBox}>
                    <div style={styles.hinweis}>
                      {t('scan_points_last', {
                        text: `${scanState.letzteBuchung.deltaText} ${t('cards_catalog_cost')}`
                          + (scanState.letzteBuchung.rewardName
                            ? ` (${scanState.letzteBuchung.rewardName})` : '')
                          + ` - ${new Date(scanState.letzteBuchung.createdAt)
                              .toLocaleTimeString(localeTag(lang),
                                { hour: '2-digit', minute: '2-digit' })}`,
                      })}
                    </div>
                    <button
                      style={styles.nebenKnopf}
                      disabled={loading}
                      onClick={() => punkteAufruf('/api/points/undo',
                        { bookingId: scanState.letzteBuchung.id })}
                    >
                      {t('scan_points_undo')}
                    </button>
                  </div>
                )}
```

Import ergänzen: `import { useLang, localeTag } from '../LangContext'`.

Die Buchungs-ID wird mitgeschickt, nicht „die letzte" — sonst nimmt bei zwei Kassen im selben Laden die eine die Buchung der anderen zurück.

- [ ] **Step 4: Stile ergänzen**

```jsx
  katalog: { marginTop: '8px', marginBottom: '12px' },
  katalogTitel: { fontSize: '12px', fontWeight: '700', color: '#888', textTransform: 'uppercase', letterSpacing: '0.5px', marginBottom: '8px', textAlign: 'start' },
  praemie: { width: '100%', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '8px', padding: '14px', background: '#f8f8f8', border: '1.5px solid #e8e8e8', borderRadius: '12px', marginBottom: '6px', fontSize: '15px' },
  praemieName: { fontWeight: '700', color: '#1a1a1a' },
  praemieKosten: { fontSize: '13px', color: '#666' },
  nebenKnopf: { width: '100%', padding: '12px', background: 'transparent', color: '#3C3489', border: '1.5px solid #ddd', borderRadius: '12px', fontSize: '14px', fontWeight: '600', cursor: 'pointer', marginBottom: '10px' },
  korrekturBox: { background: '#fffdf5', border: '1.5px solid #f0e0b0', borderRadius: '12px', padding: '12px', marginBottom: '10px' },
  letzteBox: { borderTop: '1px solid #eee', paddingTop: '12px', marginTop: '4px' },
  hinweis: { fontSize: '12px', color: '#888', marginBottom: '8px', textAlign: 'start' },
```

- [ ] **Step 5: Build und Handprobe**

Run: `npm run build`, dann `npm run dev`.

Durchspielen, in dieser Reihenfolge:
1. Punktekarte scannen, `14,50` buchen. Stand steigt.
2. Nochmal scannen, letzte Buchung zurücknehmen. Stand fällt zurück.
3. Nochmal zurücknehmen versuchen — der Knopf darf nicht mehr da sein, weil die Buchung als zurückgenommen gilt.
4. Genug buchen, dass eine Prämie bezahlbar wird. Sie wird antippbar, die teureren bleiben grau mit „noch X".
5. Prämie einlösen. Stand fällt um den Preis.
6. Korrektur `-5` buchen. Stand fällt um 5 Punkte.

- [ ] **Step 6: Commit**

```bash
git add src/pages/Scanner.jsx
git commit -m "feat: Praemien einloesen, korrigieren und Buchungen zuruecknehmen"
```

---

## Abschluss Teil 2

Nach Task 6 kann der Laden die Punktekarte vollständig betreiben. Was noch fehlt, sieht der **Kunde**: die Wallet-Karte zeigt weiter Stempelfelder, die Kundenseite ebenso, und die Statistik rechnet Punkte noch nicht mit. Das ist Teil 3.

**Vor dem Push:**

```bash
npm run build
npm test
npm run lint
```

**Reihenfolge beim Deploy:** Teil 1 muss auf Render **fertig** sein, bevor das hier auf `main` geht. Vercel ist in zwei Minuten live, Render braucht zwanzig — andersherum steht eine fertige Oberfläche vor einem Server, der ihre Routen nicht kennt.

**Danach:** `2026-09-21-punktekarte-3-darstellung.md`
