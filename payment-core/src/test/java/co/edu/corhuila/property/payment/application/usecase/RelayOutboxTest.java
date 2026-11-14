package co.edu.corhuila.property.payment.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.payment.application.port.out.EventPublisher;
import co.edu.corhuila.property.payment.application.port.out.OutboxStore;
import co.edu.corhuila.property.payment.application.port.out.PendingEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-PAY-001; the events of Payment have no consumer in MVP 1 and are published anyway, with fakes of the outbox and the broker. */
class RelayOutboxTest {

    /** The outbox in memory: rows in insertion order, published or not. */
    static class FakeOutbox implements OutboxStore {
        final Map<UUID, PendingEvent> rows = new LinkedHashMap<>();
        final List<UUID> published = new ArrayList<>();
        boolean failOnMark;

        PendingEvent add(String type) {
            PendingEvent e = new PendingEvent(UUID.randomUUID(), type, "{\"eventType\":\"" + type + "\"}", "trace-1");
            rows.put(e.id(), e);
            return e;
        }

        @Override
        public List<PendingEvent> nextUnpublished(int max) {
            return rows.values().stream().filter(e -> !published.contains(e.id())).limit(max).toList();
        }

        @Override
        public void markPublished(UUID id) {
            if (failOnMark) {
                throw new IllegalStateException("the process died before the mark");
            }
            published.add(id);
        }
    }

    /** The broker in memory: what it confirmed, and whether it is down. */
    static class FakeBroker implements EventPublisher {
        final List<String> received = new ArrayList<>();
        int failAfter = Integer.MAX_VALUE;

        @Override
        public void publish(String routingKey, PendingEvent event) {
            if (received.size() >= failAfter) {
                throw new PublishException("broker down", null);
            }
            received.add(routingKey + " " + event.id());
        }
    }

    final FakeOutbox outbox = new FakeOutbox();
    final FakeBroker broker = new FakeBroker();

    @Test
    void publishesInOrderWithTheRoutingKeyOfEachEventAndMarksThem() {
        var a = outbox.add("PagoAprobado");
        var b = outbox.add("PagoRechazado");
        var c = outbox.add("PagoAprobado");
        var pass = new RelayOutbox(outbox, broker, 10).runOnce();
        assertEquals(new RelayOutbox.Pass(3, false, 3), pass);
        assertEquals(List.of("pagos.pago.aprobado " + a.id(), "pagos.pago.rechazado " + b.id(),
                "pagos.pago.aprobado " + c.id()), broker.received);
        assertEquals(List.of(a.id(), b.id(), c.id()), outbox.published);
    }

    @Test
    void withTheBrokerDownNothingIsMarkedAndTheRowsGoOutWhenItComesBack() {
        outbox.add("PagoAprobado");
        outbox.add("PagoRechazado");
        broker.failAfter = 0;
        var relay = new RelayOutbox(outbox, broker, 10);
        assertEquals(new RelayOutbox.Pass(0, true, 2), relay.runOnce());
        assertTrue(outbox.published.isEmpty());
        broker.failAfter = Integer.MAX_VALUE;                      // the broker is back
        assertEquals(2, relay.runOnce().published());
        assertEquals(2, outbox.published.size());
    }

    @Test
    void aFailureStopsThePassSoTheOrderIsKept() {
        var a = outbox.add("PagoAprobado");
        outbox.add("PagoRechazado");
        outbox.add("PagoRechazado");
        broker.failAfter = 1;
        assertEquals(new RelayOutbox.Pass(1, true, 3), new RelayOutbox(outbox, broker, 10).runOnce());
        assertEquals(List.of(a.id()), outbox.published);
    }

    @Test
    void aCrashBetweenTheConfirmAndTheMarkPublishesTheEventAgainNeverLosesIt() {
        var a = outbox.add("PagoAprobado");
        outbox.failOnMark = true;
        var relay = new RelayOutbox(outbox, broker, 10);
        assertThrows(IllegalStateException.class, relay::runOnce);
        outbox.failOnMark = false;                                 // the relay restarts
        relay.runOnce();
        assertEquals(2, broker.received.size(), "at least once: a duplicate, which consumers drop by eventId");
        assertEquals(List.of(a.id()), outbox.published);
    }

    @Test
    void readsAtMostTheBatchSize() {
        for (int i = 0; i < 5; i++) {
            outbox.add("PagoAprobado");
        }
        assertEquals(2, new RelayOutbox(outbox, broker, 2).runOnce().published());
        assertThrows(IllegalArgumentException.class, () -> new RelayOutbox(outbox, broker, 0));
    }
}
