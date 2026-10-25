package com.example.stemplekarte.service;

import com.example.stemplekarte.model.Card;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.model.Reward;

import java.util.List;

/**
 * Wie weit ein Kunde mit seiner Karte ist, als Bausteine fuer Texte.
 * Sperrbildschirm-Erinnerung und Rueckhol-Nachricht nutzen dieselben
 * Platzhalter - ein Laden soll sie nur einmal lernen muessen.
 *
 * Bei Punktekarten traegt "offen" die fehlenden Punkte und "belohnung" den
 * Namen der naechsten Praemie. Ein Text, den ein Laden fuer seine
 * Stempelkarte geschrieben hat, laeuft damit unveraendert auch auf einer
 * Punktekarte.
 */
public record KartenFortschritt(String belohnung, String offen, String einheit, boolean erreicht) {

    /** Null bei einer Punktekarte ohne Praemie - dann gibt es kein Ziel zu nennen. */
    public static KartenFortschritt von(Card card, CustomerCard cc, RewardService rewardService) {
        if (card.isPoints()) {
            List<Reward> katalog = rewardService.list(card);
            Reward ziel = RewardService.naechstesZiel(katalog, cc.getPointsX100());
            if (ziel == null) return null;
            long fehlend = Math.max(0, ziel.getCostPointsX100() - cc.getPointsX100());
            return new KartenFortschritt(ziel.getName(), PointsMath.formatiere(fehlend),
                    "Punkte", fehlend == 0);
        }
        int fehlend = card.getRewardThreshold() - cc.getStamps();
        return new KartenFortschritt(card.getRewardText(), String.valueOf(Math.max(0, fehlend)),
                "Stempel", fehlend <= 0);
    }

    /** Deutsche und englische Schreibweise, so wie sie in der Oberflaeche steht. */
    public String ersetze(String text) {
        return text
                .replace("{belohnung}", belohnung)
                .replace("{reward}", belohnung)
                .replace("{stempel}", offen)
                .replace("{stamps}", offen);
    }
}
