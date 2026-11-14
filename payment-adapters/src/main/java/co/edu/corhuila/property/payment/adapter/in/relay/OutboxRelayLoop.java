package co.edu.corhuila.property.payment.adapter.in.relay;

import co.edu.corhuila.property.payment.application.usecase.RelayOutbox;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the relay until the process stops: after a batch that published something the next
 * one is read at once; an empty batch waits the poll interval; a broker failure waits longer each time,
 * up to a ceiling, and nothing is lost meanwhile (the rows stay unpublished).
 */
public class OutboxRelayLoop {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayLoop.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final RelayOutbox relay;
    private final Duration poll;
    private volatile boolean running = true;

    public OutboxRelayLoop(RelayOutbox relay, Duration poll) {
        this.relay = relay;
        this.poll = poll;
    }

    public void run() throws InterruptedException {
        log.info("outbox relay started");
        int failures = 0;
        while (running) {
            Duration wait = poll;
            try {
                RelayOutbox.Pass pass = relay.runOnce();
                if (pass.published() > 0) {
                    log.info("outbox relay published={} pending={}", pass.published(), pass.pending());
                }
                if (pass.failed()) {
                    failures++;
                    wait = backoff(failures);
                    log.warn("broker unavailable, {} event(s) wait for the next attempt in {} ms",
                            pass.pending() - pass.published(), wait.toMillis());
                } else {
                    failures = 0;
                    if (pass.published() > 0) {
                        continue;                         // there may be more: no wait
                    }
                }
            } catch (RuntimeException e) {
                failures++;
                wait = backoff(failures);
                log.error("outbox relay pass failed", e);
            }
            Thread.sleep(wait.toMillis());
        }
        log.info("outbox relay stopped");
    }

    public void stop() {
        running = false;
    }

    private Duration backoff(int failures) {
        long ms = poll.toMillis() * (1L << Math.min(failures, 5));
        return Duration.ofMillis(Math.min(ms, MAX_BACKOFF.toMillis()));
    }
}
