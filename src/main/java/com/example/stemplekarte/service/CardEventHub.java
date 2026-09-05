package com.example.stemplekarte.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live-Push des Stempelstands an die offene Kunden-Kartenseite (/karte/...).
 *
 * Der Scan laeuft auf dem Personal-Geraet, die Kartenseite haengt beim Gast.
 * Bisher aktualisierte sie sich nur per Polling (alle paar Sekunden) - der
 * Gast sah den neuen Stempel also mit Verzoegerung. Hier haelt jede offene
 * Kartenseite eine SSE-Verbindung; nach dem Scan wird der neue Stand sofort
 * gepusht.
 *
 * In-Memory, ein Prozess. Laeuft nur solange EINE Render-Instanz aktiv ist
 * (aktuell der Fall). Bei mehreren Instanzen muesste das ueber einen
 * gemeinsamen Bus laufen - dann faellt es einfach auf das Polling zurueck.
 */
@Service
public class CardEventHub {

    private static final Logger log = LoggerFactory.getLogger(CardEventHub.class);

    // Schluessel = customerCard-ID (CC-...). Mehrere offene Tabs pro Karte moeglich.
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // 30 Min, dann reconnectet der Browser

    public SseEmitter subscribe(String customerCardId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = emitters.computeIfAbsent(customerCardId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> remove(customerCardId, emitter));
        emitter.onTimeout(() -> remove(customerCardId, emitter));
        emitter.onError(e -> remove(customerCardId, emitter));

        try {
            emitter.send(SseEmitter.event().name("hello").data("ok"));
        } catch (IOException e) {
            remove(customerCardId, emitter);
        }
        return emitter;
    }

    /** Neuen Stempelstand an alle offenen Kartenseiten dieser Karte pushen. */
    public void publishStamps(String customerCardId, int stamps, int totalRewards, int threshold) {
        List<SseEmitter> list = emitters.get(customerCardId);
        if (list == null || list.isEmpty()) return;

        String json = String.format(
                "{\"stamps\":%d,\"totalRewards\":%d,\"threshold\":%d}",
                stamps, totalRewards, threshold);

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("stamps").data(json));
            } catch (Exception e) {
                remove(customerCardId, emitter);
            }
        }
        log.debug("SSE-Push stamps={} an {} Verbindung(en) fuer {}", stamps, list.size(), customerCardId);
    }

    private void remove(String customerCardId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(customerCardId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) emitters.remove(customerCardId);
        }
    }

    /** Heartbeat, damit Proxys (Render) die Leerlauf-Verbindung nicht kappen. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        emitters.forEach((key, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(key, emitter);
                }
            }
        });
    }
}
