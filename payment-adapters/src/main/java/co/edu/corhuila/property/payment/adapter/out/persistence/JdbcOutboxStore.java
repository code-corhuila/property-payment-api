package co.edu.corhuila.property.payment.adapter.out.persistence;

import co.edu.corhuila.property.payment.application.port.out.OutboxStore;
import co.edu.corhuila.property.payment.application.port.out.PendingEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * payment.outbox_event as payment_app (ADR-005). The partial index
 * idx_outbox_event_unpublished serves the read. One relay runs per environment, so the
 * rows are read in order without locks.
 */
public class JdbcOutboxStore implements OutboxStore {

    private final JdbcTemplate jdbc;

    public JdbcOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PendingEvent> nextUnpublished(int max) {
        return jdbc.query("SELECT id, event_type, payload::text AS payload, "
                        + "payload->'metadata'->>'correlationId' AS correlation_id "
                        + "FROM payment.outbox_event WHERE published = FALSE ORDER BY created_at, id LIMIT ?",
                (rs, n) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("payload"), rs.getString("correlation_id")), max);
    }

    @Override
    public void markPublished(UUID id) {
        jdbc.update("UPDATE payment.outbox_event SET published = TRUE, published_at = now() WHERE id = ?", id);
    }
}
