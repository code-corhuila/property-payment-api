package co.edu.corhuila.property.payment.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.payment.application.port.out.PendingEvent;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The outbox as the relay reads it, against the REAL schema payment as payment_app: the
 * grants allow reading and marking. Skipped without TEST_DATABASE_URL (Annex C).
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcOutboxStoreIntegrationTest {

    JdbcTemplate jdbc;
    JdbcOutboxStore outbox;

    @BeforeEach
    void connect() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),
                System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD")));
        outbox = new JdbcOutboxStore(jdbc);
        // Earlier tests of the suite leave rows: this test reads only its own, by id.
    }

    UUID insert(String type, String correlationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payment.outbox_event (id, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)",
                id, UUID.randomUUID(), type,
                "{\"eventId\":\"" + id + "\",\"metadata\":{\"correlationId\":\"" + correlationId + "\"}}");
        return id;
    }

    @Test
    void readsTheUnpublishedOldestFirstWithTheCorrelationIdAndMarksThem() {
        UUID first = insert("PagoAprobado", "it-relay-1");
        UUID second = insert("PagoRechazado", "it-relay-1");
        var pending = outbox.nextUnpublished(10_000).stream().map(PendingEvent::id).toList();
        assertTrue(pending.indexOf(first) < pending.indexOf(second), "oldest first");
        PendingEvent row = outbox.nextUnpublished(10_000).stream().filter(e -> e.id().equals(first)).findFirst().orElseThrow();
        assertEquals("PagoAprobado", row.eventType());
        assertEquals("it-relay-1", row.correlationId());
        assertTrue(row.payload().contains(first.toString()));

        outbox.markPublished(first);
        assertFalse(outbox.nextUnpublished(10_000).stream().anyMatch(e -> e.id().equals(first)));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT published AND published_at IS NOT NULL FROM payment.outbox_event WHERE id = ?", Boolean.class, first));
        outbox.markPublished(second);
    }
}
