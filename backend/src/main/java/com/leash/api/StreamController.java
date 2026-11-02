package com.leash.api;

import com.leash.ledger.LedgerEvent;
import com.leash.mandate.MandateService.LeashEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live updates for the dashboard (Server-Sent Events). Events: "ledger" (an entry was created or changed) and
 * "leash" (a leash was created, changed, snapped or re-leashed). Sent after the transaction commits, so the dashboard
 * never shows something that was rolled back. EventSource reconnects by itself if the connection drops.
 */
@RestController
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);
    private static final long TIMEOUT = Duration.ofMinutes(30).toMillis();

    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();

    @GetMapping(value = "/api/stream", produces = "text/event-stream")
    public SseEmitter stream() throws IOException {
        SseEmitter emitter = new SseEmitter(TIMEOUT);
        clients.add(emitter);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(e -> clients.remove(emitter));
        emitter.send(SseEmitter.event().name("hello").data("{\"ok\":true}"));
        return emitter;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onLedger(LedgerEvent e) {
        broadcast("ledger", e);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onLeash(LeashEvent e) {
        broadcast("leash", e.leash());
    }

    /** Keeps proxies (and Render's load balancer) from closing an idle stream. */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        for (SseEmitter c : clients) {
            try {
                c.send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException ex) {
                clients.remove(c);
            }
        }
    }

    public int clientCount() {
        return clients.size();
    }

    private void broadcast(String name, Object data) {
        for (SseEmitter c : clients) {
            try {
                c.send(SseEmitter.event().name(name).data(data));
            } catch (IOException | IllegalStateException ex) {
                log.debug("Dropping stream client: {}", ex.getMessage());
                clients.remove(c);
            }
        }
    }
}
