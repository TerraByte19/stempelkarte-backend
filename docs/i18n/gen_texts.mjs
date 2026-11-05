// Erzeugt src/main/java/.../i18n/Texts.java aus den JSON-Dateien in diesem Ordner.
// Aufruf: node docs/i18n/gen_texts.mjs
//
// Dateien: kunden_de_en.json {key: {de, en}}, kunden_ar.json und kunden_tr.json {key: text}.
// Liegen neu_de_en.json / neu_ar.json / neu_tr.json daneben, werden sie vorher
// in die kunden_*.json gemischt und geloescht (so kommen neue Schluessel rein).
// Fehlt eine Uebersetzung, steht vorerst Deutsch drin - mit Warnung hier.
import { readFileSync, writeFileSync, existsSync, unlinkSync } from 'node:fs'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
const S = dirname(fileURLToPath(import.meta.url))
const OUT = `${S}/../../src/main/java/com/example/stemplekarte/i18n/Texts.java`

const lies = p => JSON.parse(readFileSync(p, 'utf8'))
const schreib = (p, o) => writeFileSync(p, JSON.stringify(o, null, 1) + '\n')

// Neue Schluessel einmischen
let deEn = lies(`${S}/kunden_de_en.json`)
if (existsSync(`${S}/neu_de_en.json`)) {
  const neu = lies(`${S}/neu_de_en.json`)
  let alle = true
  for (const l of ['ar', 'tr']) if (!existsSync(`${S}/neu_${l}.json`)) alle = false
  deEn = { ...deEn, ...neu }
  schreib(`${S}/kunden_de_en.json`, deEn)
  for (const l of ['ar', 'tr']) {
    const p = `${S}/neu_${l}.json`
    if (!existsSync(p)) continue
    const voll = existsSync(`${S}/kunden_${l}.json`) ? lies(`${S}/kunden_${l}.json`) : {}
    schreib(`${S}/kunden_${l}.json`, { ...voll, ...lies(p) })
    unlinkSync(p)
  }
  if (alle) unlinkSync(`${S}/neu_de_en.json`)
  else console.log('neu_de_en.json bleibt liegen, bis neu_ar.json und neu_tr.json da sind')
}

const keys = Object.keys(deEn)
const langs = { de: {}, en: {} }
for (const k of keys) { langs.de[k] = deEn[k].de; langs.en[k] = deEn[k].en }
const ph = s => (s.match(/\{\d\}/g) || []).sort().join('')
for (const l of ['ar', 'tr']) {
  const p = `${S}/kunden_${l}.json`
  const m = existsSync(p) ? lies(p) : {}
  const zuviel = Object.keys(m).filter(k => !(k in deEn))
  if (zuviel.length) throw new Error(`${l}: Schluessel ohne de: ${zuviel.join(',')}`)
  const fehlt = []
  langs[l] = {}
  for (const k of keys) {
    const v = m[k]
    if (typeof v !== 'string' || !v.trim()) { fehlt.push(k); langs[l][k] = deEn[k].de; continue }
    if (ph(v) !== ph(deEn[k].de)) throw new Error(`${l}.${k}: Platzhalter ${ph(v)} statt ${ph(deEn[k].de)}`)
    if ((v.includes('%@')) !== (deEn[k].de.includes('%@'))) throw new Error(`${l}.${k}: %@ fehlt oder zu viel`)
    langs[l][k] = v
  }
  if (fehlt.length) console.log(`WARNUNG ${l}: ${fehlt.length} Schluessel noch Deutsch: ${fehlt.slice(0, 8).join(', ')}${fehlt.length > 8 ? ' ...' : ''}`)
}

const esc = s => s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
const methode = (l, m) => {
  const z = [`    private static Map<String, String> ${l}() {`, `        Map<String, String> m = new HashMap<>();`]
  for (const k of keys) z.push(`        m.put("${k}", "${esc(m[k])}");`)
  z.push(`        return Map.copyOf(m);`, `    }`)
  return z.join('\n')
}
const java = `package com.example.stemplekarte.i18n;

import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.Shop;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Texte, die der Endkunde sieht: Kartenseite, Anmeldeseite, Mail-Seiten,
 * Mails, Wallet-Karte und Mitteilungen. Spiegel von i18n.js im Frontend,
 * nur fuer das Backend.
 *
 * GENERIERT aus docs/i18n/*.json (node docs/i18n/gen_texts.mjs) - Aenderungen
 * an den Texten bitte dort und neu erzeugen.
 *
 * Sprache: Customer.language (beim Anmelden vom Laden uebernommen), sonst
 * Shop.language, sonst Deutsch. Unbekannte Schluessel fallen auf Deutsch
 * zurueck, unbekannte Sprachen auch. Platzhalter {0}, {1} ... ; "%@" bleibt
 * stehen, das ersetzt Apple selbst.
 */
public final class Texts {

    public static final String STANDARD = "de";
    public static final Set<String> SPRACHEN = Set.of("de", "en", "ar", "tr");

    private static final Map<String, Map<String, String>> T = Map.of(
            "de", de(), "en", en(), "ar", ar(), "tr", tr());

    private Texts() {}

    /** Reihenfolge der Sprachleiste auf den Kundenseiten. */
    public static final java.util.List<String> LEISTE = java.util.List.of("de", "en", "ar", "tr");

    /** Unbekannt oder leer = Deutsch. */
    public static String norm(String lang) {
        if (lang == null) return STANDARD;
        String l = lang.trim().toLowerCase(Locale.ROOT);
        return SPRACHEN.contains(l) ? l : STANDARD;
    }

    public static boolean isRtl(String lang) {
        return "ar".equals(norm(lang));
    }

    /** lang="xx" dir="ltr|rtl" fuer das html-Element, mit fuehrendem Leerzeichen. */
    public static String htmlAttrs(String lang) {
        String l = norm(lang);
        return " lang=\\"" + l + "\\" dir=\\"" + (isRtl(l) ? "rtl" : "ltr") + "\\"";
    }

    /**
     * Sprache des Kunden, sonst Deutsch. Entscheidung User 2026-11-05: jeder
     * Kunde startet auf Deutsch und schaltet auf der Anmelde- oder
     * Kartenseite selbst um (DE, EN, AR, TR); die Wahl bleibt gespeichert.
     * Shop.language spielt fuer Kundenseiten keine Rolle mehr.
     */
    public static String sprache(Customer customer, Shop shop) {
        if (customer != null && customer.getLanguage() != null && !customer.getLanguage().isBlank()) {
            return norm(customer.getLanguage());
        }
        return STANDARD;
    }

    /** Ohne Kunden (Anmeldeseite): Deutsch. */
    public static String sprache(Shop shop) {
        return STANDARD;
    }

    /** Nur eine der vier Sprachen, sonst null (fuer ?lang= und das Formularfeld). */
    public static String gewaehlt(String lang) {
        if (lang == null) return null;
        String l = lang.trim().toLowerCase(Locale.ROOT);
        return SPRACHEN.contains(l) ? l : null;
    }

    public static String t(String lang, String key, Object... vars) {
        String base = T.get(norm(lang)).get(key);
        if (base == null) base = T.get(STANDARD).get(key);
        if (base == null) return key;
        for (int i = 0; i < vars.length; i++) {
            base = base.replace("{" + i + "}", String.valueOf(vars[i]));
        }
        return base;
    }

    /** Fuer Tests: alle Schluessel einer Sprache. */
    public static Map<String, String> alle(String lang) {
        return T.get(norm(lang));
    }

${['de', 'en', 'ar', 'tr'].map(l => methode(l, langs[l])).join('\n\n')}
}
`
writeFileSync(OUT, java.replace(/\r?\n/g, '\r\n'))
console.log('Texts.java:', keys.length, 'Schluessel')
