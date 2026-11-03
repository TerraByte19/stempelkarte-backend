package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Reward;

import java.util.List;
import java.util.function.Function;

/**
 * Platzhalter in Nachrichtentexten, je Karte beim Versand gefuellt:
 * {name} Vorname, {offen} fehlende Stempel, {praemie} Praemientext bzw.
 * naechste erreichbare Praemie der Punktekarte. Fehlt der Wert, bleibt
 * die Stelle leer - und ein " ," oder doppeltes Leerzeichen wird geglaettet,
 * damit "Danke, {name}!" ohne Namen nicht als "Danke, !" ankommt.
 */
public final class Platzhalter {

    private Platzhalter() {}

    public static boolean enthaelt(String text) {
        return text != null && (text.contains("{name}") || text.contains("{offen}")
                || text.contains("{praemie}") || text.contains("{stufe}"));
    }

    public static String fuelle(String text, CustomerCard cc, Function<Card, List<Reward>> katalog) {
        if (!enthaelt(text)) return text;
        Card card = cc.getCard();
        String out = text;
        if (out.contains("{name}")) out = out.replace("{name}", vorname(cc));
        if (out.contains("{offen}")) out = out.replace("{offen}", offen(card, cc));
        if (out.contains("{praemie}")) out = out.replace("{praemie}", praemie(card, cc, katalog));
        if (out.contains("{stufe}")) out = out.replace("{stufe}", stufe(card, cc));
        // "Treue, !" -> "Treue!", "wartet: ." -> "wartet.", doppelte Leerzeichen weg.
        return out.replaceAll(" +([,.!?:])", "$1").replaceAll("[,:]\\s*([.!?])", "$1")
                .replaceAll(" {2,}", " ").trim();
    }

    static String vorname(CustomerCard cc) {
        String name = cc.getCustomer() != null ? cc.getCustomer().getName() : null;
        if (name == null) return "";
        String[] teile = name.trim().split("\\s+");
        return teile.length > 0 ? teile[0] : "";
    }

    /** Name der aktuellen Stufe (Bronze, Silber, Gold), sonst leer. */
    static String stufe(Card card, CustomerCard cc) {
        Stufen.Stufe s = Stufen.von(card, cc);
        return s != null ? s.name() : "";
    }

    static String offen(Card card, CustomerCard cc) {
        // 10er-Karte: was vom Paket noch uebrig ist.
        if (card.isPackage()) return String.valueOf(cc.getPaketRest());
        if (!card.isStamp()) return "";
        int offen = card.getRewardThreshold() - cc.getStamps();
        return offen > 0 ? String.valueOf(offen) : "0";
    }

    static String praemie(Card card, CustomerCard cc, Function<Card, List<Reward>> katalog) {
        if (!card.isPoints()) return card.getRewardText() != null ? card.getRewardText() : "";
        if (katalog == null) return "";
        List<Reward> liste = katalog.apply(card);
        if (liste == null || liste.isEmpty()) return "";
        // Erreichbar: die teuerste, die der Stand schon deckt; sonst die naechste.
        Reward erreichbar = liste.stream()
                .filter(r -> r.getCostPointsX100() <= cc.getPointsX100())
                .max(java.util.Comparator.comparingLong(Reward::getCostPointsX100))
                .orElse(RewardService.naechstesZiel(liste, cc.getPointsX100()));
        return erreichbar != null && erreichbar.getName() != null ? erreichbar.getName() : "";
    }
}
