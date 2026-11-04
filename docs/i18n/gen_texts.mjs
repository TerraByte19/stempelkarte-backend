// Erzeugt src/main/java/.../i18n/Texts.java aus den JSON-Dateien in diesem Ordner.
// Aufruf: node docs/i18n/gen_texts.mjs
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
const S = dirname(fileURLToPath(import.meta.url))
const OUT = `${S}/../../src/main/java/com/example/stemplekarte/i18n/Texts.java`

const deEn = JSON.parse(readFileSync(`${S}/kunden_de_en.json`, 'utf8'))
const keys = Object.keys(deEn)
const langs = { de: {}, en: {} }
for (const k of keys) { langs.de[k] = deEn[k].de; langs.en[k] = deEn[k].en }
for (const l of ['ar', 'tr']) {
  const p = `${S}/kunden_${l}.json`
  if (existsSync(p)) {
    const m = JSON.parse(readFileSync(p, 'utf8'))
    const fehlt = keys.filter(k => !(k in m) || typeof m[k] !== 'string' || !m[k].trim())
    const zuviel = Object.keys(m).filter(k => !(k in deEn))
    if (fehlt.length || zuviel.length) throw new Error(`${l}: fehlt ${fehlt.join(',')} / zu viel ${zuviel.join(',')}`)
    const ph = s => (s.match(/\{\d\}/g) || []).sort().join('')
    for (const k of keys) if (ph(m[k]) !== ph(deEn[k].de)) throw new Error(`${l}.${k}: Platzhalter ${ph(m[k])} statt ${ph(deEn[k].de)}`)
    langs[l] = m
  } else {
    console.log(`(${l} fehlt noch, Rueckfall auf de)`)
  }
}

const esc = s => s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
const methode = (l, m) => {
  const z = [`    private static Map<String, String> ${l}() {`, `        Map<String, String> m = new HashMap<>();`]
  for (const k of keys) z.push(`        m.put("${k}", "${esc(m[k])}");`)
  z.push(`        return Map.copyOf(m);`, `    }`)
  return z.join('\n')
}
const vorhanden = Object.keys(langs)
const java = `package com.example.stemplekarte.i18n;

import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.Shop;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Texte, die der Endkunde sieht: Kartenseite, Anmeldeseite, Mail-Seiten und
 * Mails. Spiegel von i18n.js im Frontend, nur fuer das Backend.
 *
 * GENERIERT aus scratchpad/kunden/*.json (gen_texts.mjs) - Aenderungen an den
 * Texten bitte dort und neu erzeugen, oder hier UND in allen Sprachen.
 *
 * Sprache: Customer.language (beim Anmelden vom Laden uebernommen), sonst
 * Shop.language, sonst Deutsch. Unbekannte Schluessel fallen auf Deutsch
 * zurueck, unbekannte Sprachen auch. Platzhalter {0}, {1} ...
 */
public final class Texts {

    public static final String STANDARD = "de";
    public static final Set<String> SPRACHEN = Set.of("de", "en", "ar", "tr");

    private static final Map<String, Map<String, String>> T = Map.of(
            "de", de(), "en", en(),
            "ar", ${vorhanden.includes('ar') ? 'ar()' : 'de()'},
            "tr", ${vorhanden.includes('tr') ? 'tr()' : 'de()'});

    private Texts() {}

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

    /** Kunde vor Laden: wer sich bei einem deutschen Laden angemeldet hat, bleibt deutsch. */
    public static String sprache(Customer customer, Shop shop) {
        if (customer != null && customer.getLanguage() != null && !customer.getLanguage().isBlank()) {
            return norm(customer.getLanguage());
        }
        return sprache(shop);
    }

    public static String sprache(Shop shop) {
        return shop == null ? STANDARD : norm(shop.getLanguageOrDefault());
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

${vorhanden.map(l => methode(l, langs[l])).join('\n\n')}
}
`
writeFileSync(OUT, java.replace(/\r?\n/g, '\r\n'))
console.log('Texts.java:', keys.length, 'Schluessel, Sprachen', vorhanden.join(','))
