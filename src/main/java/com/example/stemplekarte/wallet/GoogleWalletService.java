package com.example.stemplekarte.wallet;

import com.example.stemplekarte.i18n.Texts;

import com.example.stemplekarte.config.AppProperties;
import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.model.Reward;
import com.example.stemplekarte.repository.CustomerCardRepository;
import com.example.stemplekarte.service.PointsMath;
import com.example.stemplekarte.service.RewardService;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.walletobjects.Walletobjects;
import com.google.api.client.util.Data;
import com.google.api.services.walletobjects.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class GoogleWalletService {

    private static final Logger log = LoggerFactory.getLogger(GoogleWalletService.class);
    private static final String SAVE_URL_BASE = "https://pay.google.com/gp/v/save/";
    private static final String SCOPE = "https://www.googleapis.com/auth/wallet_object.issuer";

    private final AppProperties props;
    private final CustomerCardRepository customerCardRepo;
    private final RewardService rewardService;
    private final ServiceAccountCredentials credentials;
    private final Walletobjects walletClient;

    public GoogleWalletService(AppProperties props, CustomerCardRepository customerCardRepo,
                               RewardService rewardService) {
        this.rewardService = rewardService;
        this.props = props;
        this.customerCardRepo = customerCardRepo;
        this.credentials = loadCredentials();
        this.walletClient = buildWalletClient();
    }

    private ServiceAccountCredentials loadCredentials() {
        try (var fis = new FileInputStream(props.google().serviceAccountPath())) {
            return (ServiceAccountCredentials) GoogleCredentials
                    .fromStream(fis)
                    .createScoped(List.of(SCOPE));
        } catch (Exception e) {
            log.warn("Google Service Account nicht geladen: {}", e.getMessage());
            return null;
        }
    }

    private Walletobjects buildWalletClient() {
        if (credentials == null) return null;
        try {
            return new Walletobjects.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("Stempelkarte")
                    .build();
        } catch (Exception e) {
            log.warn("Google Wallet Client konnte nicht erstellt werden: {}", e.getMessage());
            return null;
        }
    }

    private String classIdFor(Shop shop) {
        return props.google().issuerId() + ".shop_" + shop.getId().replace("-", "_");
    }

    private String objectIdFor(CustomerCard cc) {
        return props.google().issuerId() + "." + cc.getId().replace("-", "_");
    }

    public String generateSaveUrl(CustomerCard cc) {
        if (credentials == null || walletClient == null) {
            throw new IllegalStateException("Google Service Account nicht konfiguriert.");
        }

        Shop shop = cc.getCard().getShop();
        String classId = classIdFor(shop);
        String objectId = objectIdFor(cc);

        try {
            createOrUpdateClass(cc.getCard(), classId);
            createOrUpdateObject(cc, classId, objectId);
        } catch (Exception e) {
            log.error("Google Wallet Fehler im Lifecycle: {}", e.getMessage());
        }

        PrivateKey privateKey = credentials.getPrivateKey();

        Map<String, Object> loyaltyObject = Map.of(
                "id", objectId,
                "classId", classId
        );
        Map<String, Object> payload = Map.of(
                "loyaltyObjects", List.of(loyaltyObject)
        );

        return SAVE_URL_BASE + Jwts.builder()
                .issuer(credentials.getClientEmail())
                .audience().single("google")
                .claim("typ", "savetowallet")
                .claim("origins", List.of(
                        "https://stempelkarte-backend.onrender.com",
                        "https://stempelkarte-frontend.vercel.app",
                        "https://stampit-app.de",
                        "https://www.stampit-app.de"
                ))
                .claim("payload", payload)
                .issuedAt(new Date())
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Was der Kunde nach einem Update als Mitteilung sieht. Google meldet
     * geaenderte Felder nur mit eigenem Text ("Points hat sich veraendert");
     * einen eigenen Text gibt es nur ueber addMessage.
     */
    public enum Meldung { KEINE, STEMPEL, VOLL, PUNKTE, BESUCH, PAKET, AUFGELADEN }

    @Async
    @Transactional
    public void notifyUpdate(String customerCardId) {
        notifyUpdateIntern(customerCardId, Meldung.KEINE);
    }

    @Async
    @Transactional
    public void notifyUpdate(String customerCardId, Meldung meldung) {
        notifyUpdateIntern(customerCardId, meldung);
    }

    private void notifyUpdateIntern(String customerCardId, Meldung meldung) {
        if (credentials == null || walletClient == null) return;
        CustomerCard cc;
        try {
            cc = customerCardRepo.findById(customerCardId)
                    .orElseThrow(() -> new NoSuchElementException("CustomerCard nicht gefunden: " + customerCardId));
            String classId = classIdFor(cc.getCard().getShop());
            // Auch die Class aktualisieren, damit Design-Änderungen ankommen
            createOrUpdateClass(cc.getCard(), classId);
            createOrUpdateObject(cc, classId, objectIdFor(cc));
        } catch (Exception e) {
            log.error("Google Wallet Update Fehler: {}", e.getMessage());
            return;
        }
        // Erst nach dem Objekt: die Mitteilung soll nicht vor dem neuen Stand
        // auf dem Handy sein. Eigener try - der Stand ist hier schon bei Google.
        if (meldung != null && meldung != Meldung.KEINE) {
            meldungSenden(cc, meldung);
        }
    }

    /**
     * Header und Text der Mitteilung. Statisch fuer den Test; null = nichts
     * zu melden.
     */
    static String[] meldungText(CustomerCard cc, Meldung meldung) {
        Card card = cc.getCard();
        String laden = card.getShop().getName();
        String lang = Texts.sprache(cc.getCustomer(), card.getShop());
        return switch (meldung) {
            case STEMPEL -> new String[]{
                    Texts.t(lang, "gw.stamp_header", laden),
                    Texts.t(lang, "gw.stamp_body", Math.min(cc.getStamps(), card.getRewardThreshold()), card.getRewardThreshold())};
            case VOLL -> new String[]{
                    Texts.t(lang, "gw.full_header"),
                    Texts.t(lang, "gw.full_body", card.getRewardText())};
            case PUNKTE -> new String[]{
                    Texts.t(lang, "gw.points_header", laden),
                    Texts.t(lang, "gw.points_body", PointsMath.formatiere(cc.getPointsX100()))};
            case BESUCH -> new String[]{
                    Texts.t(lang, "gw.visit_header", laden),
                    Texts.t(lang, "gw.visit_body", cc.getStamps())};
            case PAKET -> new String[]{
                    Texts.t(lang, "gw.debit_header", laden),
                    cc.getPaketRest() > 0
                            ? com.example.stemplekarte.service.PaketService.restText(lang, card, cc) + "."
                            : Texts.t(lang, "package.used_up")};
            case AUFGELADEN -> new String[]{
                    Texts.t(lang, "gw.loaded_header", laden),
                    Texts.t(lang, "gw.loaded_body", card.paketText(), com.example.stemplekarte.service.PaketService.restText(lang, card, cc))};
            case KEINE -> null;
        };
    }

    /**
     * TEXT_AND_NOTIFY: nur damit zeigt Android eine Mitteilung. Die Nachricht
     * verschwindet nach einem Tag wieder aus der Karte, sonst sammelte sich
     * dort bei jedem Stempel eine an. Google laesst hoechstens drei Mitteilungen
     * pro Karte und Tag durch; darueber lehnt es ab - dann bleibt es beim
     * stillen Update, das schon draussen ist.
     */
    private void meldungSenden(CustomerCard cc, Meldung meldung) {
        String[] text = meldungText(cc, meldung);
        if (text == null) return;
        String objectId = objectIdFor(cc);
        try {
            java.time.Instant jetzt = java.time.Instant.now();
            Message message = new Message()
                    .setHeader(text[0])
                    .setBody(text[1])
                    .setMessageType("TEXT_AND_NOTIFY")
                    .setId(meldung.name().toLowerCase() + "-" + cc.getId() + "-" + jetzt.toEpochMilli())
                    .setDisplayInterval(new TimeInterval()
                            .setStart(new DateTime().setDate(jetzt.toString()))
                            .setEnd(new DateTime().setDate(jetzt.plus(java.time.Duration.ofDays(1)).toString())));
            walletClient.loyaltyobject()
                    .addmessage(objectId, new AddMessageRequest().setMessage(message))
                    .execute();
            log.info("Google Wallet Mitteilung {} gesendet: {}", meldung, objectId);
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            // 404 = Karte liegt nicht in Google Wallet, Normalfall bei iPhone-Kunden.
            if (e.getStatusCode() != 404) {
                log.warn("Google Wallet Mitteilung {} abgelehnt ({}), Stand ist trotzdem aktuell: {}",
                        meldung, e.getStatusCode(), objectId);
            }
        } catch (Exception e) {
            log.warn("Google Wallet Mitteilung {} fehlgeschlagen: {}", meldung, e.getMessage());
        }
    }

    /**
     * Sendet eine zusätzliche, eigenständige Benachrichtigung "Karte voll!"
     * an das Gerät — unabhängig vom normalen notifyUpdate() (Stempelstand).
     * Google Wallet zeigt Objekt-Feld-Änderungen NICHT automatisch als
     * Benachrichtigung an (anders als Apples changeMessage), daher der
     * explizite addMessage-Aufruf hier.
     */
    @Async
    @Transactional
    public void notifyCardFull(String customerCardId, String rewardText) {
        if (credentials == null || walletClient == null) return;
        try {
            CustomerCard cc = customerCardRepo.findById(customerCardId)
                    .orElseThrow(() -> new NoSuchElementException("CustomerCard nicht gefunden: " + customerCardId));
            String objectId = objectIdFor(cc);

            String lang = Texts.sprache(cc.getCustomer(), cc.getCard().getShop());
            Message message = new Message()
                    .setHeader(Texts.t(lang, "gw.full_header"))
                    .setBody(Texts.t(lang, "gw.full_body", rewardText))
                    .setMessageType("TEXT")
                    .setId("reward-" + customerCardId + "-" + System.currentTimeMillis());

            walletClient.loyaltyobject()
                    .addmessage(objectId, new AddMessageRequest().setMessage(message))
                    .execute();

            log.info("Google Wallet 'Karte voll'-Benachrichtigung gesendet: {}", objectId);
        } catch (Exception e) {
            log.error("Google Wallet 'Karte voll'-Benachrichtigung Fehler: {}", e.getMessage());
        }
    }

    /**
     * "Karte freigeschaltet" nach dem Willkommensgeschenk. Wie notifyCardFull:
     * Google meldet geaenderte Felder nicht von selbst.
     */
    @Async
    @Transactional
    public void notifyFreigeschaltet(String customerCardId) {
        if (credentials == null || walletClient == null) return;
        try {
            CustomerCard cc = customerCardRepo.findById(customerCardId)
                    .orElseThrow(() -> new NoSuchElementException("CustomerCard nicht gefunden: " + customerCardId));
            String objectId = objectIdFor(cc);

            String lang = Texts.sprache(cc.getCustomer(), cc.getCard().getShop());
            Message message = new Message()
                    .setHeader(Texts.t(lang, "gw.unlocked_header"))
                    .setBody(Texts.t(lang, "gw.unlocked_body", cc.getCard().getShop().getName()))
                    .setMessageType("TEXT_AND_NOTIFY")
                    .setId("welcome-" + customerCardId + "-" + System.currentTimeMillis());

            walletClient.loyaltyobject()
                    .addmessage(objectId, new AddMessageRequest().setMessage(message))
                    .execute();
            log.info("Google Wallet 'Freigeschaltet'-Nachricht gesendet: {}", objectId);
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            // 404 = Kunde hat die Karte nie in Google Wallet gelegt. Normalfall.
            if (e.getStatusCode() != 404) {
                log.error("Google Wallet 'Freigeschaltet' Fehler: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("Google Wallet 'Freigeschaltet' Fehler: {}", e.getMessage());
        }
    }

    /**
     * Rueckhol-Nachricht (RueckholService). TEXT_AND_NOTIFY statt TEXT: nur
     * damit zeigt Android eine Benachrichtigung, sonst steht die Nachricht
     * nur still in der Karte. Google laesst davon hoechstens drei pro Tag
     * und Karte durch - eine pro Pause liegt weit darunter.
     */
    @Async
    @Transactional
    public void sendeNachricht(String customerCardId, String titel, String text) {
        sendeNachricht(customerCardId, titel, text, "winback");
    }

    /** kennung: Praefix der Message-ID, damit sich im Log Rueckhol und Kampagne unterscheiden lassen. */
    @Async
    @Transactional
    public void sendeNachricht(String customerCardId, String titel, String text, String kennung) {
        if (credentials == null || walletClient == null) return;
        try {
            CustomerCard cc = customerCardRepo.findById(customerCardId)
                    .orElseThrow(() -> new NoSuchElementException("CustomerCard nicht gefunden: " + customerCardId));
            String objectId = objectIdFor(cc);

            Message message = new Message()
                    .setHeader(titel)
                    .setBody(text)
                    .setMessageType("TEXT_AND_NOTIFY")
                    .setId(kennung + "-" + customerCardId + "-" + System.currentTimeMillis());

            walletClient.loyaltyobject()
                    .addmessage(objectId, new AddMessageRequest().setMessage(message))
                    .execute();
            log.info("Google Wallet Rueckhol-Nachricht gesendet: {}", objectId);
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            // 404 = Kunde hat die Karte nie in Google Wallet gelegt. Normalfall.
            if (e.getStatusCode() != 404) {
                log.error("Google Wallet Rueckhol-Nachricht Fehler: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("Google Wallet Rueckhol-Nachricht Fehler: {}", e.getMessage());
        }
    }

    /**
     * Schreibt das Design (Farbe, Logo, Hero, Name) einer Karte in die
     * Google Wallet Class neu — damit Änderungen aus dem Design-Panel
     * sofort bei allen bereits gespeicherten Karten ankommen.
     */
    @Async
    public void refreshClassForCard(Card card) {
        if (credentials == null || walletClient == null) return;
        try {
            String classId = classIdFor(card.getShop());
            createOrUpdateClass(card, classId);
            log.info("Google Wallet Class nach Design-Aenderung aktualisiert: {}", classId);
        } catch (Exception e) {
            log.error("Google Wallet Class Refresh Fehler: {}", e.getMessage());
        }
    }

    private void createOrUpdateClass(Card card, String classId) throws Exception {
        Shop shop = card.getShop();

        String bgColor = card.getColorBackground() != null ? card.getColorBackground() : shop.getColorBackground();
        String logoUrl = (card.getLogoUrl() != null && !card.getLogoUrl().isBlank()) ? card.getLogoUrl() : shop.getLogoUrl();
        String heroUrl = (card.getHeroImageUrl() != null && !card.getHeroImageUrl().isBlank()) ? card.getHeroImageUrl() : shop.getHeroImageUrl();

        LoyaltyClass lc = new LoyaltyClass()
                .setId(classId)
                .setIssuerName(shop.getName())
                .setProgramName(card.getName())
                .setHexBackgroundColor(safeColor(bgColor))
                .setReviewStatus("underReview")
                .setCountryCode("DE");

        if (isImageReachable(logoUrl)) {
            lc.setProgramLogo(new Image().setSourceUri(new ImageUri().setUri(logoUrl)));
        }
        if (isImageReachable(heroUrl)) {
            lc.setHeroImage(new Image().setSourceUri(new ImageUri().setUri(heroUrl)));
        }

        try {
            walletClient.loyaltyclass().get(classId).execute();
            walletClient.loyaltyclass().patch(classId, lc).execute();
            log.info("Google Wallet Class aktualisiert: {}", classId);
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                try {
                    walletClient.loyaltyclass().insert(lc).execute();
                    log.info("Google Wallet Class erstellt: {}", classId);
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException ex) {
                    if (ex.getStatusCode() == 409) {
                        log.warn("Google Wallet Class parallel erstellt (409), wechsle zu Patch: {}", classId);
                        walletClient.loyaltyclass().patch(classId, lc).execute();
                    } else {
                        throw ex;
                    }
                }
            } else if (e.getStatusCode() == 409) {
                log.warn("Google Wallet Class Concurrency Conflict (409) bei Patch: {}", classId);
                walletClient.loyaltyclass().patch(classId, lc).execute();
            } else {
                throw e;
            }
        }
    }

    private void createOrUpdateObject(CustomerCard cc, String classId, String objectId) throws Exception {
        // Konstanter, kurzer QR-Inhalt "SK:<Kundenkarte>" (QrPayload.fuer).
        // Alte Karten mit {"cid","cardId"} liest der Scanner weiter.
        String qrValue = com.example.stemplekarte.service.QrPayload.fuer(cc);

        Card card = cc.getCard();
        String lang = Texts.sprache(cc.getCustomer(), card.getShop());

        LoyaltyPoints haupt;
        LoyaltyPoints neben;
        // Immer gesetzt, auch leer: so wird ein alter Baustein bei einer
        // bestehenden Karte sauber mit nichts ueberschrieben.
        List<TextModuleData> module = new ArrayList<>();

        if (card.isMember()) {
            // Mitgliedskarte: Besuche und "Mitglied seit", kein Ziel.
            String seit = cc.getCreatedAt() != null
                    ? java.time.format.DateTimeFormatter.ofPattern("MM/yyyy")
                            .format(cc.getCreatedAt().atZone(java.time.ZoneId.of("Europe/Berlin")))
                    : "";
            haupt = new LoyaltyPoints()
                    .setLabel(Texts.t(lang, "gw.visits"))
                    .setBalance(new LoyaltyPointsBalance().setString(String.valueOf(cc.getStamps())));
            neben = new LoyaltyPoints()
                    .setLabel(Texts.t(lang, "gw.member_since"))
                    .setBalance(new LoyaltyPointsBalance().setString(seit));
        } else if (card.isPackage()) {
            // 10er-Karte: Rest und Paketgroesse.
            haupt = new LoyaltyPoints()
                    .setLabel(Texts.t(lang, "gw.left"))
                    .setBalance(new LoyaltyPointsBalance().setString(String.valueOf(cc.getPaketRest())));
            neben = new LoyaltyPoints()
                    .setLabel(Texts.t(lang, "gw.package"))
                    .setBalance(new LoyaltyPointsBalance().setString(card.paketText()));
        } else if (card.isPoints()) {
            long stand = cc.getPointsX100();
            List<Reward> katalog = rewardService.list(card);
            Reward ziel = RewardService.naechstesZiel(katalog, stand);

            haupt = new LoyaltyPoints()
                    .setLabel(Texts.t(lang, "gw.points"))
                    .setBalance(new LoyaltyPointsBalance()
                            .setString(PointsMath.formatiere(stand)));

            if (ziel != null) {
                long fehlend = Math.max(0, ziel.getCostPointsX100() - stand);
                neben = new LoyaltyPoints()
                        .setLabel(Texts.t(lang, "gw.next_reward"))
                        .setBalance(new LoyaltyPointsBalance()
                                .setString(fehlend > 0
                                        ? Texts.t(lang, "gw.next_reward_left", ziel.getName(), PointsMath.formatiere(fehlend))
                                        : Texts.t(lang, "gw.next_reward_ready", ziel.getName())));
            } else {
                neben = new LoyaltyPoints()
                        .setLabel(Texts.t(lang, "gw.rewards"))
                        .setBalance(new LoyaltyPointsBalance().setString(Texts.t(lang, "gw.rewards_none")));
            }

            // Der Katalog als Textbaustein - das Gegenstueck zur
            // Pass-Rueckseite bei Apple.
            if (!katalog.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Reward r : katalog) {
                    boolean bezahlbar = r.getCostPointsX100() <= stand;
                    sb.append(bezahlbar ? "\u2713 " : "\u00b7 ")
                      .append(r.getName()).append(" - ")
                      .append(PointsMath.formatiere(r.getCostPointsX100()))
                      .append(" ").append(Texts.t(lang, "unit.points")).append("\n");
                }
                module.add(new TextModuleData()
                        .setId("katalog")
                        .setHeader(Texts.t(lang, "gw.rewards"))
                        .setBody(sb.toString().trim()));
            }
        } else if (cc.isWillkommenOffen()) {
            // Gesperrt: Geschenk statt Stempelstand. Der QR bleibt derselbe.
            LoyaltyPoints[] felder = willkommenFelder(cc);
            haupt = felder[0];
            neben = felder[1];
        } else {
            // Unveraendert der bisherige Stempel-Weg.
            haupt = new LoyaltyPoints()
                    .setLabel(Texts.t(lang, "gw.stamps"))
                    .setBalance(new LoyaltyPointsBalance()
                            // Gedeckelt: gesenkte Schwelle wuerde sonst "10/5" anzeigen.
                            .setString(Math.min(cc.getStamps(), card.getRewardThreshold())
                                    + "/" + card.getRewardThreshold()));
            neben = new LoyaltyPoints()
                    .setLabel(Texts.t(lang, "gw.reward"))
                    .setBalance(new LoyaltyPointsBalance()
                            .setString(card.getRewardText()));
        }

        // Tagesangebot als Textbaustein, nur solange es gilt. Die Mitteilung
        // dazu kommt ueber die Kampagne; abgelaufen faellt der Baustein beim
        // naechsten Objekt-Update von selbst weg (module wird komplett gesetzt).
        String heute = card.getShop() != null ? card.getShop().tagesangebot(java.time.Instant.now()) : null;
        if (heute != null) {
            module.add(new TextModuleData().setId("heute").setHeader(Texts.t(lang, "gw.today")).setBody(heute));
        }
        // Stufe (Bronze, Silber, Gold), nur bei Karten mit Stufen.
        com.example.stemplekarte.service.Stufen.Stufe stufe = com.example.stemplekarte.service.Stufen.von(card, cc);
        if (stufe != null) {
            module.add(new TextModuleData().setId("status").setHeader(Texts.t(lang, "gw.status"))
                    .setBody(com.example.stemplekarte.service.Stufen.text(
                            lang, stufe, com.example.stemplekarte.service.Stufen.besuche(card, cc))));
        }
        // Kunde wirbt Kunde: Satz plus Link mit dem Code dieser Karte.
        if (com.example.stemplekarte.service.EmpfehlungService.sichtbar(card, cc)) {
            module.add(new TextModuleData().setId("empfehlen").setHeader(Texts.t(lang, "gw.referral"))
                    .setBody(com.example.stemplekarte.service.EmpfehlungService.werbeText(lang, card) + " " + Texts.t(lang, "pass.referral_link")
                            + com.example.stemplekarte.service.EmpfehlungService.linkFuer(
                                    props.baseUrl(), card.getId(), cc.getEmpfehlungsCode())));
        }

        LoyaltyObject loyaltyObject = new LoyaltyObject()
                .setId(objectId)
                .setClassId(classId)
                .setState("ACTIVE")
                .setAccountName(cc.getCustomer().getName())
                .setAccountId(cc.getId())
                .setLoyaltyPoints(haupt)
                .setSecondaryLoyaltyPoints(neben)
                .setTextModulesData(module)
                // setAlternateText("") überschreibt den alten CUST-Text bei bestehenden Karten mit leer
                .setBarcode(new Barcode()
                        .setType("QR_CODE")
                        .setValue(qrValue)
                        .setAlternateText(""));

        // Willkommensbild nur am Objekt dieses Kunden, nie an der Klasse
        // (die teilen sich alle Karten des Ladens). Der HEAD-Abruf laeuft nur,
        // wenn ueberhaupt ein Bild in Frage kommt.
        boolean bildErreichbar = cc.isWillkommenOffen() && isImageReachable(card.getWelcomeImageUrl());
        Image hero = willkommenHero(cc, bildErreichbar);
        if (hero != null) loyaltyObject.setHeroImage(hero);

        try {
            walletClient.loyaltyobject().get(objectId).execute();
            walletClient.loyaltyobject().patch(objectId, loyaltyObject).execute();
            log.info("Google Wallet Objekt aktualisiert: {}", objectId);
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                try {
                    walletClient.loyaltyobject().insert(loyaltyObject).execute();
                    log.info("Google Wallet Objekt erstellt: {}", objectId);
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException ex) {
                    if (ex.getStatusCode() == 409) {
                        log.warn("Google Wallet Objekt parallel erstellt (409), wechsle zu Patch: {}", objectId);
                        walletClient.loyaltyobject().patch(objectId, loyaltyObject).execute();
                    } else {
                        throw ex;
                    }
                }
            } else if (e.getStatusCode() == 409) {
                log.warn("Google Wallet Objekt Concurrency Conflict (409) bei Patch: {}", objectId);
                walletClient.loyaltyobject().patch(objectId, loyaltyObject).execute();
            } else {
                throw e;
            }
        }
    }

    /**
     * Haupt- und Nebenfeld einer gesperrten Karte. Paketsichtbar fuer den Test.
     *
     * Abgeschaltetes wird explizit null gesetzt statt weggelassen: beim Patch
     * bliebe ein weggelassenes Feld mit seinem alten Text stehen.
     */
    static LoyaltyPoints[] willkommenFelder(CustomerCard cc) {
        String geschenk = cc.getWillkommenText() != null ? cc.getWillkommenText() : Card.WILLKOMMEN_STANDARD;
        Card card = cc.getCard();
        String titel = card.zeigtWillkommenTitel() ? card.getWillkommenTitel() : null;

        LoyaltyPoints haupt;
        if (card.zeigtWillkommenGeschenk()) {
            haupt = new LoyaltyPoints()
                    .setLabel(titel != null ? titel : Data.NULL_STRING)
                    .setBalance(new LoyaltyPointsBalance().setString(geschenk));
        } else if (titel != null) {
            haupt = new LoyaltyPoints()
                    .setLabel(Data.NULL_STRING)
                    .setBalance(new LoyaltyPointsBalance().setString(titel));
        } else {
            haupt = Data.nullOf(LoyaltyPoints.class);
        }

        LoyaltyPoints neben = card.zeigtWillkommenHinweis()
                ? new LoyaltyPoints()
                        .setLabel("So geht's")
                        .setBalance(new LoyaltyPointsBalance().setString(card.getWillkommenHinweis()))
                : Data.nullOf(LoyaltyPoints.class);
        return new LoyaltyPoints[] { haupt, neben };
    }

    /**
     * Hero-Bild am Objekt.
     *
     * null = Feld nicht senden: Karten ohne Willkommens-Historie bleiben
     * genau wie bisher. Gesperrt mit erreichbarem Bild = das Bild. Sonst ein
     * explizites JSON-null: beim Patch bliebe ein weggelassenes Feld stehen,
     * und das Willkommensbild klebte nach dem Freischalten auf der
     * Stempelkarte. Ohne Objekt-Bild zeigt Google wieder das der Klasse.
     */
    static Image willkommenHero(CustomerCard cc, boolean bildErreichbar) {
        boolean historie = cc.isWillkommenOffen() || cc.getWillkommenEingeloestAt() != null;
        if (!historie) return null;
        if (cc.isWillkommenOffen() && bildErreichbar) {
            return new Image().setSourceUri(new ImageUri().setUri(cc.getCard().getWelcomeImageUrl()));
        }
        return Data.nullOf(Image.class);
    }

    private boolean isHttps(String url) {
        return url != null && url.startsWith("https://");
    }

    private boolean isImageReachable(String url) {
        if (!isHttps(url)) return false;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String safeColor(String c) {
        return (c != null && c.startsWith("#")) ? c : "#3C3489";
    }
}