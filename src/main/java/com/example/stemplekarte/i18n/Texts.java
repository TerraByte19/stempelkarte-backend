package com.example.stemplekarte.i18n;

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
            "ar", ar(),
            "tr", tr());

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
        return " lang=\"" + l + "\" dir=\"" + (isRtl(l) ? "rtl" : "ltr") + "\"";
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

    private static Map<String, String> de() {
        Map<String, String> m = new HashMap<>();
        m.put("card.apple", "🍎 Zu Apple Wallet hinzufügen");
        m.put("card.google", "🤖 Zu Google Wallet hinzufügen");
        m.put("card.not_found", "Karte nicht gefunden");
        m.put("card.review_google", "⭐ Bewerte uns bei Google");
        m.put("card.feedback", "💬 Wie war's? Sag es {0}");
        m.put("card.status", "🏅 Dein Status: ");
        m.put("card.demo_title", "🧪 Demo");
        m.put("card.demo_text", "Spiel die Kasse: ein Tipp = ein Besuch im Laden.");
        m.put("card.demo_next", "Nächster Schritt");
        m.put("card.demo_error", "Fehler");
        m.put("card.ref_title", "👥 Freunde werben");
        m.put("card.ref_code", "Dein Code: ");
        m.put("card.ref_share", "Link teilen");
        m.put("card.ref_copied", "Link kopiert");
        m.put("stamp.title", "Treuekarte");
        m.put("stamp.progress", "{0} von {1} Stempeln");
        m.put("stamp.reward_ready", "🎉 {0} verfügbar!");
        m.put("stamp.reward_left", "Noch {0} Stempel bis: {1}");
        m.put("stamp.welcome_text", "Zeig diese Karte an der Kasse. Danach sammelst du hier deine Stempel.");
        m.put("points.title", "Punktekarte");
        m.put("points.balance", "Punktestand");
        m.put("points.rewards", "Prämien");
        m.put("points.next", "Nächste Prämie: {0} - noch {1}");
        m.put("points.none", "Noch keine Prämie hinterlegt");
        m.put("points.ready", "{0} ist bereit!");
        m.put("points.empty", "Dieser Laden hat noch keine Prämien hinterlegt. Deine Punkte sammeln sich trotzdem.");
        m.put("points.unit", "Punkte");
        m.put("member.title", "Mitgliedskarte");
        m.put("member.label", "Mitglied");
        m.put("member.since", "Mitglied seit");
        m.put("member.visits", "Besuche");
        m.put("package.title", "10er-Karte");
        m.put("package.left", "Noch übrig");
        m.put("package.used_up", "Dein Paket ist aufgebraucht. Ein neues gibt's an der Kasse.");
        m.put("package.none", "Noch kein Paket geladen. Dein erstes Paket gibt's an der Kasse.");
        m.put("package.package", "Paket");
        m.put("package.used", "Genutzt");
        m.put("package.name", "Name");
        m.put("signup.title", "Stempelkarte");
        m.put("signup.headline", "🎉 Stempelkarte holen!");
        m.put("signup.text", "Trag deinen Namen und deine E-Mail ein und bekomme die Stempelkarte direkt in deine Wallet!");
        m.put("signup.text_gift", "Trag deinen Namen und deine E-Mail ein und hol dir die Karte in die Wallet. Zeig sie an der Kasse, und das Geschenk gehört dir. Danach sammelst du Stempel.");
        m.put("signup.name", "Dein Name");
        m.put("signup.email", "Deine E-Mail");
        m.put("signup.birthday", "Geburtstag");
        m.put("signup.phone", "Handynummer");
        m.put("signup.optional", " (optional)");
        m.put("signup.ref", "Empfehlungscode (optional)");
        m.put("signup.consent", "Ich möchte Angebote von {0} per E-Mail erhalten. Abmeldung jederzeit über den Link in jeder Mail möglich.");
        m.put("signup.button", "Karte holen 🎴");
        m.put("signup.sent_title", "Fast geschafft!");
        m.put("signup.sent_text", "Wir haben dir eine E-Mail geschickt.<br>Klick auf den Link darin, um deine Stempelkarte direkt aufs Handy zu bekommen.");
        m.put("signup.err_fields", "Bitte Name und E-Mail eingeben");
        m.put("signup.err_birthday", "Bitte Geburtstag angeben");
        m.put("signup.err_phone", "Bitte Handynummer angeben");
        m.put("signup.err_generic", "Fehler - bitte nochmal versuchen");
        m.put("page.close_hint", "Du kannst dieses Fenster schließen.");
        m.put("page.confirmed_title", "E-Mail bestätigt");
        m.put("page.confirmed_text", "Danke! Deine E-Mail-Adresse ist bestätigt. Du kannst dieses Fenster schließen.");
        m.put("page.delete_sent_title", "Bestätigungs-Mail gesendet");
        m.put("page.delete_sent_text", "Falls ein Konto zu dieser Anfrage existiert, haben wir eine E-Mail mit einem Bestätigungs-Link geschickt. Erst nach Klick auf diesen Link werden die Daten gelöscht.");
        m.put("page.deleted_title", "Daten gelöscht");
        m.put("page.deleted_text", "Dein Konto, alle Stempelkarten und alle gespeicherten Daten wurden gelöscht.");
        m.put("page.invalid_title", "Link ungültig");
        m.put("page.delete_invalid_text", "Dieser Lösch-Link ist ungültig oder wurde bereits verwendet.");
        m.put("page.unsub_title", "Abgemeldet");
        m.put("page.unsub_text", "Du erhältst keine Angebote mehr von {0}. Deine Stempelkarte funktioniert ganz normal weiter.");
        m.put("page.unsub_invalid_text", "Dieser Abmelde-Link ist ungültig.");
        m.put("mail.confirm_subject", "Deine Stempelkarte – {0}");
        m.put("mail.confirm_preview", "Ein Klick, und deine Stempelkarte ist auf dem Handy.");
        m.put("mail.confirm_title", "Deine Stempelkarte bei {0}");
        m.put("mail.confirm_body", "Hallo {0},<br><br>du hast dich für die digitale Stempelkarte von <b>{1}</b> angemeldet. Bestätige kurz deine E-Mail-Adresse, dann liegt die Karte in deiner Wallet.");
        m.put("mail.confirm_button", "Stempelkarte aufs Handy holen");
        m.put("mail.confirm_ignore", "Wenn du das nicht warst, ignoriere diese Mail einfach.");
        m.put("mail.gift_label", "Dein Willkommensgeschenk:");
        m.put("mail.gift_hint", "Zeig die Karte einfach an der Kasse.");
        m.put("mail.delete_subject", "Löschung bestätigen – StampIT");
        m.put("mail.delete_preview", "Löschung deiner Daten bestätigen.");
        m.put("mail.delete_title", "Löschung bestätigen");
        m.put("mail.delete_body", "Hallo {0},<br><br>du hast die Löschung deiner Daten angefordert. <b>Achtung:</b> Damit werden dein Konto, alle Stempelkarten und alle gesammelten Stempel unwiderruflich gelöscht.");
        m.put("mail.delete_button", "Meine Daten endgültig löschen");
        m.put("mail.delete_ignore", "Wenn du das nicht warst, ignoriere diese Mail - dann passiert nichts.");
        m.put("mail.footer", "Diese Mail wurde über StampIT (digitale Stempelkarten) versendet.");
        m.put("mail.footer_delete", "Ich möchte mein Konto und meine Daten löschen");
        m.put("mail.unsubscribe", "Keine Angebote mehr von {0} erhalten (abmelden)");
        m.put("mail.stand", "Dein Stand: ");
        m.put("mail.stand_of", "{0} von {1} Stempeln");
        m.put("mail.stand_ready_named", "{0} wartet auf dich.");
        m.put("mail.stand_ready", "Deine Belohnung wartet auf dich.");
        m.put("mail.stand_left_named", "Noch {0} bis: {1}.");
        m.put("mail.stand_left", "Noch {0} bis zur Belohnung.");
        return Map.copyOf(m);
    }

    private static Map<String, String> en() {
        Map<String, String> m = new HashMap<>();
        m.put("card.apple", "🍎 Add to Apple Wallet");
        m.put("card.google", "🤖 Add to Google Wallet");
        m.put("card.not_found", "Card not found");
        m.put("card.review_google", "⭐ Rate us on Google");
        m.put("card.feedback", "💬 How was it? Tell {0}");
        m.put("card.status", "🏅 Your status: ");
        m.put("card.demo_title", "🧪 Demo");
        m.put("card.demo_text", "Play the till: one tap = one visit to the shop.");
        m.put("card.demo_next", "Next step");
        m.put("card.demo_error", "Error");
        m.put("card.ref_title", "👥 Invite friends");
        m.put("card.ref_code", "Your code: ");
        m.put("card.ref_share", "Share link");
        m.put("card.ref_copied", "Link copied");
        m.put("stamp.title", "Loyalty card");
        m.put("stamp.progress", "{0} of {1} stamps");
        m.put("stamp.reward_ready", "🎉 {0} available!");
        m.put("stamp.reward_left", "{0} more stamps until: {1}");
        m.put("stamp.welcome_text", "Show this card at the till. After that you collect your stamps here.");
        m.put("points.title", "Points card");
        m.put("points.balance", "Points balance");
        m.put("points.rewards", "Rewards");
        m.put("points.next", "Next reward: {0} - {1} to go");
        m.put("points.none", "No reward set up yet");
        m.put("points.ready", "{0} is ready!");
        m.put("points.empty", "This shop has not set up any rewards yet. Your points still add up.");
        m.put("points.unit", "points");
        m.put("member.title", "Membership card");
        m.put("member.label", "Member");
        m.put("member.since", "Member since");
        m.put("member.visits", "Visits");
        m.put("package.title", "Punch card");
        m.put("package.left", "Remaining");
        m.put("package.used_up", "Your package is used up. Get a new one at the till.");
        m.put("package.none", "No package loaded yet. Get your first one at the till.");
        m.put("package.package", "Package");
        m.put("package.used", "Used");
        m.put("package.name", "Name");
        m.put("signup.title", "Stamp card");
        m.put("signup.headline", "🎉 Get your stamp card!");
        m.put("signup.text", "Enter your name and email and get the stamp card straight into your wallet!");
        m.put("signup.text_gift", "Enter your name and email and get the card into your wallet. Show it at the till and the gift is yours. After that you collect stamps.");
        m.put("signup.name", "Your name");
        m.put("signup.email", "Your email");
        m.put("signup.birthday", "Birthday");
        m.put("signup.phone", "Mobile number");
        m.put("signup.optional", " (optional)");
        m.put("signup.ref", "Referral code (optional)");
        m.put("signup.consent", "I would like to receive offers from {0} by email. You can unsubscribe any time via the link in every email.");
        m.put("signup.button", "Get card 🎴");
        m.put("signup.sent_title", "Almost there!");
        m.put("signup.sent_text", "We have sent you an email.<br>Tap the link in it to get your stamp card straight onto your phone.");
        m.put("signup.err_fields", "Please enter your name and email");
        m.put("signup.err_birthday", "Please enter your birthday");
        m.put("signup.err_phone", "Please enter your mobile number");
        m.put("signup.err_generic", "Error - please try again");
        m.put("page.close_hint", "You can close this window.");
        m.put("page.confirmed_title", "Email confirmed");
        m.put("page.confirmed_text", "Thanks! Your email address is confirmed. You can close this window.");
        m.put("page.delete_sent_title", "Confirmation email sent");
        m.put("page.delete_sent_text", "If an account exists for this request, we have sent an email with a confirmation link. Your data is only deleted after you click that link.");
        m.put("page.deleted_title", "Data deleted");
        m.put("page.deleted_text", "Your account, all stamp cards and all stored data have been deleted.");
        m.put("page.invalid_title", "Invalid link");
        m.put("page.delete_invalid_text", "This deletion link is invalid or has already been used.");
        m.put("page.unsub_title", "Unsubscribed");
        m.put("page.unsub_text", "You will no longer receive offers from {0}. Your stamp card keeps working as usual.");
        m.put("page.unsub_invalid_text", "This unsubscribe link is invalid.");
        m.put("mail.confirm_subject", "Your stamp card – {0}");
        m.put("mail.confirm_preview", "One tap and your stamp card is on your phone.");
        m.put("mail.confirm_title", "Your stamp card at {0}");
        m.put("mail.confirm_body", "Hi {0},<br><br>you signed up for the digital stamp card from <b>{1}</b>. Confirm your email address and the card goes straight into your wallet.");
        m.put("mail.confirm_button", "Get the stamp card on my phone");
        m.put("mail.confirm_ignore", "If this wasn't you, just ignore this email.");
        m.put("mail.gift_label", "Your welcome gift:");
        m.put("mail.gift_hint", "Just show the card at the till.");
        m.put("mail.delete_subject", "Confirm deletion – StampIT");
        m.put("mail.delete_preview", "Confirm the deletion of your data.");
        m.put("mail.delete_title", "Confirm deletion");
        m.put("mail.delete_body", "Hi {0},<br><br>you asked us to delete your data. <b>Please note:</b> this permanently deletes your account, all stamp cards and all collected stamps.");
        m.put("mail.delete_button", "Delete my data permanently");
        m.put("mail.delete_ignore", "If this wasn't you, ignore this email - nothing will happen.");
        m.put("mail.footer", "This email was sent via StampIT (digital stamp cards).");
        m.put("mail.footer_delete", "I want to delete my account and my data");
        m.put("mail.unsubscribe", "Stop receiving offers from {0} (unsubscribe)");
        m.put("mail.stand", "Your progress: ");
        m.put("mail.stand_of", "{0} of {1} stamps");
        m.put("mail.stand_ready_named", "{0} is waiting for you.");
        m.put("mail.stand_ready", "Your reward is waiting for you.");
        m.put("mail.stand_left_named", "{0} more until: {1}.");
        m.put("mail.stand_left", "{0} more until your reward.");
        return Map.copyOf(m);
    }

    private static Map<String, String> ar() {
        Map<String, String> m = new HashMap<>();
        m.put("card.apple", "🍎 أضِف إلى Apple Wallet");
        m.put("card.google", "🤖 أضِف إلى Google Wallet");
        m.put("card.not_found", "البطاقة غير موجودة");
        m.put("card.review_google", "⭐ قيّمنا على Google");
        m.put("card.feedback", "💬 كيف كانت تجربتك؟ أخبر {0}");
        m.put("card.status", "🏅 مستواك: ");
        m.put("card.demo_title", "🧪 تجربة");
        m.put("card.demo_text", "جرّب دور الكاشير: كل نقرة = زيارة واحدة للمتجر.");
        m.put("card.demo_next", "الخطوة التالية");
        m.put("card.demo_error", "خطأ");
        m.put("card.ref_title", "👥 ادعُ أصدقاءك");
        m.put("card.ref_code", "رمزك: ");
        m.put("card.ref_share", "شارك الرابط");
        m.put("card.ref_copied", "تم نسخ الرابط");
        m.put("stamp.title", "بطاقة الولاء");
        m.put("stamp.progress", "{0} من {1} أختام");
        m.put("stamp.reward_ready", "🎉 {0} بانتظارك!");
        m.put("stamp.reward_left", "باقي {0} أختام حتى: {1}");
        m.put("stamp.welcome_text", "أظهر هذه البطاقة عند الكاشير، وبعدها تجمع أختامك هنا.");
        m.put("points.title", "بطاقة النقاط");
        m.put("points.balance", "رصيد النقاط");
        m.put("points.rewards", "المكافآت");
        m.put("points.next", "المكافأة التالية: {0} - باقي {1}");
        m.put("points.none", "لا توجد مكافأة بعد");
        m.put("points.ready", "{0} بانتظارك!");
        m.put("points.empty", "لم يُضِف هذا المتجر أي مكافآت بعد، لكن نقاطك تتجمّع رغم ذلك.");
        m.put("points.unit", "نقاط");
        m.put("member.title", "بطاقة العضوية");
        m.put("member.label", "عضو");
        m.put("member.since", "عضو منذ");
        m.put("member.visits", "الزيارات");
        m.put("package.title", "بطاقة العشر زيارات");
        m.put("package.left", "المتبقي");
        m.put("package.used_up", "انتهت باقتك. احصل على باقة جديدة عند الكاشير.");
        m.put("package.none", "لا توجد باقة بعد. احصل على باقتك الأولى عند الكاشير.");
        m.put("package.package", "الباقة");
        m.put("package.used", "المستخدَم");
        m.put("package.name", "الاسم");
        m.put("signup.title", "بطاقة الأختام");
        m.put("signup.headline", "🎉 احصل على بطاقة الأختام!");
        m.put("signup.text", "أدخل اسمك وبريدك الإلكتروني واحصل على بطاقة الأختام مباشرة في محفظتك!");
        m.put("signup.text_gift", "أدخل اسمك وبريدك الإلكتروني وأضِف البطاقة إلى محفظتك. أظهرها عند الكاشير والهدية لك. بعد ذلك تبدأ بجمع الأختام.");
        m.put("signup.name", "اسمك");
        m.put("signup.email", "بريدك الإلكتروني");
        m.put("signup.birthday", "تاريخ الميلاد");
        m.put("signup.phone", "رقم الجوال");
        m.put("signup.optional", " (اختياري)");
        m.put("signup.ref", "رمز الدعوة (اختياري)");
        m.put("signup.consent", "أرغب في تلقي عروض {0} عبر البريد الإلكتروني. يمكنني إلغاء الاشتراك في أي وقت عبر الرابط الموجود في كل رسالة.");
        m.put("signup.button", "احصل على البطاقة 🎴");
        m.put("signup.sent_title", "خطوة أخيرة!");
        m.put("signup.sent_text", "أرسلنا إليك رسالة بريد إلكتروني.<br>اضغط على الرابط فيها لتحصل على بطاقة الأختام مباشرة على جوالك.");
        m.put("signup.err_fields", "يرجى إدخال الاسم والبريد الإلكتروني");
        m.put("signup.err_birthday", "يرجى إدخال تاريخ الميلاد");
        m.put("signup.err_phone", "يرجى إدخال رقم الجوال");
        m.put("signup.err_generic", "حدث خطأ - يرجى المحاولة مرة أخرى");
        m.put("page.close_hint", "يمكنك إغلاق هذه النافذة.");
        m.put("page.confirmed_title", "تم تأكيد البريد الإلكتروني");
        m.put("page.confirmed_text", "شكرًا! تم تأكيد بريدك الإلكتروني. يمكنك إغلاق هذه النافذة.");
        m.put("page.delete_sent_title", "تم إرسال رسالة التأكيد");
        m.put("page.delete_sent_text", "إذا كان هناك حساب مرتبط بهذا الطلب، فقد أرسلنا رسالة بريد إلكتروني تحتوي على رابط تأكيد. لن تُحذف البيانات إلا بعد الضغط على هذا الرابط.");
        m.put("page.deleted_title", "تم حذف البيانات");
        m.put("page.deleted_text", "تم حذف حسابك وجميع بطاقات الأختام وكل البيانات المحفوظة.");
        m.put("page.invalid_title", "رابط غير صالح");
        m.put("page.delete_invalid_text", "رابط الحذف هذا غير صالح أو تم استخدامه من قبل.");
        m.put("page.unsub_title", "تم إلغاء الاشتراك");
        m.put("page.unsub_text", "لن تصلك عروض من {0} بعد الآن. ستبقى بطاقة الأختام تعمل كالمعتاد.");
        m.put("page.unsub_invalid_text", "رابط إلغاء الاشتراك هذا غير صالح.");
        m.put("mail.confirm_subject", "بطاقة الأختام الخاصة بك – {0}");
        m.put("mail.confirm_preview", "نقرة واحدة وتصبح بطاقة الأختام على جوالك.");
        m.put("mail.confirm_title", "بطاقة الأختام الخاصة بك لدى {0}");
        m.put("mail.confirm_body", "مرحبًا {0}،<br><br>لقد سجّلت في بطاقة الأختام الرقمية من <b>{1}</b>. أكّد بريدك الإلكتروني الآن، وستصبح البطاقة في محفظتك.");
        m.put("mail.confirm_button", "احصل على بطاقة الأختام على جوالك");
        m.put("mail.confirm_ignore", "إذا لم تكن أنت من قام بذلك، فتجاهل هذه الرسالة ببساطة.");
        m.put("mail.gift_label", "هدية الترحيب الخاصة بك:");
        m.put("mail.gift_hint", "فقط أظهر البطاقة عند الكاشير.");
        m.put("mail.delete_subject", "تأكيد الحذف – StampIT");
        m.put("mail.delete_preview", "أكّد حذف بياناتك.");
        m.put("mail.delete_title", "تأكيد الحذف");
        m.put("mail.delete_body", "مرحبًا {0}،<br><br>لقد طلبت حذف بياناتك. <b>تنبيه:</b> سيؤدي ذلك إلى حذف حسابك وجميع بطاقات الأختام وكل الأختام التي جمعتها نهائيًا دون إمكانية الاسترجاع.");
        m.put("mail.delete_button", "احذف بياناتي نهائيًا");
        m.put("mail.delete_ignore", "إذا لم تكن أنت من طلب ذلك، فتجاهل هذه الرسالة - ولن يحدث شيء.");
        m.put("mail.footer", "أُرسلت هذه الرسالة عبر StampIT (بطاقات أختام رقمية).");
        m.put("mail.footer_delete", "أريد حذف حسابي وبياناتي");
        m.put("mail.unsubscribe", "إيقاف تلقي العروض من {0} (إلغاء الاشتراك)");
        m.put("mail.stand", "تقدّمك: ");
        m.put("mail.stand_of", "{0} من {1} أختام");
        m.put("mail.stand_ready_named", "{0} بانتظارك.");
        m.put("mail.stand_ready", "مكافأتك بانتظارك.");
        m.put("mail.stand_left_named", "باقي {0} حتى: {1}.");
        m.put("mail.stand_left", "باقي {0} حتى المكافأة.");
        return Map.copyOf(m);
    }

    private static Map<String, String> tr() {
        Map<String, String> m = new HashMap<>();
        m.put("card.apple", "🍎 Apple Wallet'a ekle");
        m.put("card.google", "🤖 Google Wallet'a ekle");
        m.put("card.not_found", "Kart bulunamadı");
        m.put("card.review_google", "⭐ Bizi Google'da değerlendir");
        m.put("card.feedback", "💬 Nasıldı? Görüşünü {0} ile paylaş");
        m.put("card.status", "🏅 Durumun: ");
        m.put("card.demo_title", "🧪 Demo");
        m.put("card.demo_text", "Kasayı sen oyna: bir dokunuş = işletmeye bir ziyaret.");
        m.put("card.demo_next", "Sonraki adım");
        m.put("card.demo_error", "Hata");
        m.put("card.ref_title", "👥 Arkadaşını davet et");
        m.put("card.ref_code", "Kodun: ");
        m.put("card.ref_share", "Bağlantıyı paylaş");
        m.put("card.ref_copied", "Bağlantı kopyalandı");
        m.put("stamp.title", "Sadakat kartı");
        m.put("stamp.progress", "{0}/{1} damga");
        m.put("stamp.reward_ready", "🎉 {0} hazır!");
        m.put("stamp.reward_left", "{1} için {0} damga daha kaldı");
        m.put("stamp.welcome_text", "Bu kartı kasada göster. Sonra damgalarını burada toplarsın.");
        m.put("points.title", "Puan kartı");
        m.put("points.balance", "Puan durumu");
        m.put("points.rewards", "Ödüller");
        m.put("points.next", "Sıradaki ödül: {0} - {1} kaldı");
        m.put("points.none", "Henüz ödül tanımlanmadı");
        m.put("points.ready", "{0} hazır!");
        m.put("points.empty", "Bu işletme henüz ödül tanımlamadı. Puanların yine de birikiyor.");
        m.put("points.unit", "puan");
        m.put("member.title", "Üyelik kartı");
        m.put("member.label", "Üye");
        m.put("member.since", "Üyelik tarihi");
        m.put("member.visits", "Ziyaret");
        m.put("package.title", "10'luk kart");
        m.put("package.left", "Kalan");
        m.put("package.used_up", "Paketin bitti. Yenisini kasadan alabilirsin.");
        m.put("package.none", "Henüz paket yüklenmedi. İlk paketini kasadan alabilirsin.");
        m.put("package.package", "Paket");
        m.put("package.used", "Kullanılan");
        m.put("package.name", "Ad");
        m.put("signup.title", "Damga kartı");
        m.put("signup.headline", "🎉 Damga kartını al!");
        m.put("signup.text", "Adını ve e-postanı gir, damga kartın direkt cüzdanına gelsin!");
        m.put("signup.text_gift", "Adını ve e-postanı gir, kartı cüzdanına ekle. Kasada göster, hediye senin. Sonra damga toplamaya başlarsın.");
        m.put("signup.name", "Adın");
        m.put("signup.email", "E-postan");
        m.put("signup.birthday", "Doğum günü");
        m.put("signup.phone", "Cep numarası");
        m.put("signup.optional", " (isteğe bağlı)");
        m.put("signup.ref", "Davet kodu (isteğe bağlı)");
        m.put("signup.consent", "{0} tarafından gönderilen kampanyaları e-posta ile almak istiyorum. Her e-postadaki bağlantıyla istediğim zaman abonelikten çıkabilirim.");
        m.put("signup.button", "Kartı al 🎴");
        m.put("signup.sent_title", "Az kaldı!");
        m.put("signup.sent_text", "Sana bir e-posta gönderdik.<br>Damga kartını direkt telefonuna almak için içindeki bağlantıya dokun.");
        m.put("signup.err_fields", "Lütfen adını ve e-postanı gir");
        m.put("signup.err_birthday", "Lütfen doğum gününü gir");
        m.put("signup.err_phone", "Lütfen cep numaranı gir");
        m.put("signup.err_generic", "Hata - lütfen tekrar dene");
        m.put("page.close_hint", "Bu pencereyi kapatabilirsin.");
        m.put("page.confirmed_title", "E-posta onaylandı");
        m.put("page.confirmed_text", "Teşekkürler! E-posta adresin onaylandı. Bu pencereyi kapatabilirsin.");
        m.put("page.delete_sent_title", "Onay e-postası gönderildi");
        m.put("page.delete_sent_text", "Bu talebe ait bir hesap varsa, sana onay bağlantısı içeren bir e-posta gönderdik. Verilerin ancak bu bağlantıya tıkladıktan sonra silinir.");
        m.put("page.deleted_title", "Veriler silindi");
        m.put("page.deleted_text", "Hesabın, tüm damga kartların ve kayıtlı tüm verilerin silindi.");
        m.put("page.invalid_title", "Geçersiz bağlantı");
        m.put("page.delete_invalid_text", "Bu silme bağlantısı geçersiz ya da daha önce kullanılmış.");
        m.put("page.unsub_title", "Abonelikten çıkıldı");
        m.put("page.unsub_text", "{0} artık sana kampanya göndermeyecek. Damga kartın her zamanki gibi çalışmaya devam ediyor.");
        m.put("page.unsub_invalid_text", "Bu abonelikten çıkma bağlantısı geçersiz.");
        m.put("mail.confirm_subject", "Damga kartın – {0}");
        m.put("mail.confirm_preview", "Tek dokunuşla damga kartın telefonunda.");
        m.put("mail.confirm_title", "{0} damga kartın");
        m.put("mail.confirm_body", "Merhaba {0},<br><br><b>{1}</b> dijital damga kartına kaydoldun. E-posta adresini hızlıca onayla, kart hemen cüzdanına eklensin.");
        m.put("mail.confirm_button", "Damga kartını telefonuna al");
        m.put("mail.confirm_ignore", "Bunu sen yapmadıysan bu e-postayı dikkate alma.");
        m.put("mail.gift_label", "Hoş geldin hediyen:");
        m.put("mail.gift_hint", "Kartı kasada göstermen yeterli.");
        m.put("mail.delete_subject", "Silme işlemini onayla – StampIT");
        m.put("mail.delete_preview", "Verilerinin silinmesini onayla.");
        m.put("mail.delete_title", "Silme işlemini onayla");
        m.put("mail.delete_body", "Merhaba {0},<br><br>verilerinin silinmesini istedin. <b>Dikkat:</b> Bu işlemle hesabın, tüm damga kartların ve topladığın tüm damgalar kalıcı olarak silinir.");
        m.put("mail.delete_button", "Verilerimi kalıcı olarak sil");
        m.put("mail.delete_ignore", "Bunu sen yapmadıysan bu e-postayı dikkate alma - hiçbir şey olmaz.");
        m.put("mail.footer", "Bu e-posta StampIT (dijital damga kartları) üzerinden gönderildi.");
        m.put("mail.footer_delete", "Hesabımı ve verilerimi silmek istiyorum");
        m.put("mail.unsubscribe", "{0} tarafından kampanya almak istemiyorum (abonelikten çık)");
        m.put("mail.stand", "Durumun: ");
        m.put("mail.stand_of", "{0}/{1} damga");
        m.put("mail.stand_ready_named", "{0} seni bekliyor.");
        m.put("mail.stand_ready", "Ödülün seni bekliyor.");
        m.put("mail.stand_left_named", "{1} için {0} daha kaldı.");
        m.put("mail.stand_left", "Ödülüne {0} kaldı.");
        return Map.copyOf(m);
    }
}
