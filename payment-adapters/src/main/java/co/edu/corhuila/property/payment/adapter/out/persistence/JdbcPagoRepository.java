package co.edu.corhuila.property.payment.adapter.out.persistence;

import co.edu.corhuila.property.payment.application.port.out.OutboxEvent;
import co.edu.corhuila.property.payment.application.port.out.PagoDuplicadoException;
import co.edu.corhuila.property.payment.application.port.out.PagoRepository;
import co.edu.corhuila.property.payment.domain.model.EstadoPago;
import co.edu.corhuila.property.payment.domain.model.MetodoPago;
import co.edu.corhuila.property.payment.domain.model.Money;
import co.edu.corhuila.property.payment.domain.model.Pago;
import co.edu.corhuila.property.payment.domain.model.PagoId;
import co.edu.corhuila.property.payment.domain.model.Reembolso;
import co.edu.corhuila.property.payment.domain.model.ReembolsoId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger of the schema payment, as payment_app: only SELECT and INSERT on it, the
 * grants of property-payment-db (a Pago is never updated, AGGR-INV-004). It knows the
 * column names; the domain does not (models.md, naming note).
 */
public class JdbcPagoRepository implements PagoRepository {

    private static final String PAGO = "SELECT p.id, p.reservation_id, p.guest_id, p.amount_cents, p.currency, "
            + "p.payment_method, p.status, p.gateway_transaction_id, p.rejection_reason, p.created_at, k.key "
            + "FROM payment.payment p JOIN payment.idempotency_key k ON k.payment_id = p.id";
    private static final String REEMBOLSO = "SELECT r.id, r.payment_id, r.amount_cents, r.currency, "
            + "r.gateway_transaction_id, r.created_at, k.key "
            + "FROM payment.refund r JOIN payment.idempotency_key k ON k.refund_id = r.id";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcPagoRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Created<Pago> createPago(Pago p, List<OutboxEvent> events) {
        Created<Pago> created = tx.execute(status -> {
            Optional<Pago> first = findByKey(p.getIdempotencyKey());
            if (first.isPresent()) {
                return new Created<>(first.get(), false);
            }
            try {
                jdbc.update("INSERT INTO payment.payment (id, reservation_id, guest_id, amount_cents, currency, "
                                + "payment_method, status, gateway_transaction_id, rejection_reason, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        p.getId().value(), p.getReservaId(), p.getUsuarioId(), p.getMonto().getMontoCents(),
                        p.getMonto().getMoneda(), p.getMetodoPago().name(), p.getEstado().name(), p.getTransaccionId(),
                        p.getMotivoRechazo(), utc(p.getCreatedAt()));
            } catch (DuplicateKeyException e) {      // uq_payment_reservation_id: the Reserva has a Pago
                status.setRollbackOnly();
                return null;
            }
            if (!insertKey(p.getIdempotencyKey(), "payment_id", p.getId().value(), p.getCreatedAt())) {
                status.setRollbackOnly();
                return null;
            }
            for (OutboxEvent e : events) {
                jdbc.update("INSERT INTO payment.outbox_event (id, aggregate_id, aggregate_type, event_type, payload) "
                        + "VALUES (?, ?, 'Pago', ?, ?::jsonb)", e.id(), e.aggregateId(), e.eventType(), toJson(e));
            }
            return new Created<>(p, true);
        });
        if (created != null) {
            return created;
        }
        // Rolled back: a request with the same key won the race (its Pago is returned),
        // or the Reserva already has a Pago created with another key.
        return findByKey(p.getIdempotencyKey()).map(first -> new Created<>(first, false))
                .orElseThrow(PagoDuplicadoException::new);
    }

    @Override
    public Optional<Pago> findById(UUID id) {
        return jdbc.query(PAGO + " WHERE p.id = ?", (rs, n) -> pago(rs), id).stream().findFirst();
    }

    @Override
    public Optional<Pago> findByKey(String idempotencyKey) {
        return jdbc.query(PAGO + " WHERE k.key = ?", (rs, n) -> pago(rs), idempotencyKey).stream().findFirst();
    }

    @Override
    public Optional<Pago> findByReserva(UUID reservaId) {
        return jdbc.query(PAGO + " WHERE p.reservation_id = ?", (rs, n) -> pago(rs), reservaId).stream().findFirst();
    }

    @Override
    public Created<Reembolso> createReembolso(Reembolso r) {
        Boolean stored = tx.execute(status -> {
            try {
                jdbc.update("INSERT INTO payment.refund (id, payment_id, amount_cents, currency, gateway_transaction_id, "
                                + "created_at) VALUES (?, ?, ?, ?, ?, ?)", r.getId().value(), r.getPagoId().value(),
                        r.getMonto().getMontoCents(), r.getMonto().getMoneda(), r.getTransaccionId(), utc(r.getCreatedAt()));
            } catch (DuplicateKeyException e) {      // uq_refund_payment_id: refunded already (INV-006)
                status.setRollbackOnly();
                return false;
            }
            if (!insertKey(r.getIdempotencyKey(), "refund_id", r.getId().value(), r.getCreatedAt())) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        if (Boolean.TRUE.equals(stored)) {
            return new Created<>(r, true);
        }
        return findReembolsoByPago(r.getPagoId().value()).map(first -> new Created<>(first, false))
                .orElseThrow(() -> new IllegalStateException("the refund key is already used by another resource"));
    }

    @Override
    public Optional<Reembolso> findReembolsoByPago(UUID pagoId) {
        return jdbc.query(REEMBOLSO + " WHERE r.payment_id = ?", (rs, n) -> reembolso(rs), pagoId).stream().findFirst();
    }

    @Override
    public Optional<Reembolso> findReembolso(UUID id) {
        return jdbc.query(REEMBOLSO + " WHERE r.id = ?", (rs, n) -> reembolso(rs), id).stream().findFirst();
    }

    /** One key creates exactly one resource (chk_idempotency_key_one_resource). False: the key is taken. */
    private boolean insertKey(String key, String column, UUID id, Instant at) {
        return jdbc.update("INSERT INTO payment.idempotency_key (key, " + column + ", created_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (key) DO NOTHING", key, id, utc(at)) == 1;
    }

    private String toJson(OutboxEvent e) {
        try {
            return json.writeValueAsString(e.envelope());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("the event cannot be written", ex);
        }
    }

    private static Pago pago(ResultSet rs) throws SQLException {
        return Pago.restore(new PagoId(rs.getObject("id", UUID.class)), rs.getObject("reservation_id", UUID.class),
                rs.getObject("guest_id", UUID.class), new Money(rs.getLong("amount_cents"), rs.getString("currency")),
                MetodoPago.valueOf(rs.getString("payment_method")), EstadoPago.valueOf(rs.getString("status")),
                rs.getString("gateway_transaction_id"), rs.getString("rejection_reason"), rs.getString("key"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static Reembolso reembolso(ResultSet rs) throws SQLException {
        return Reembolso.restore(new ReembolsoId(rs.getObject("id", UUID.class)), new PagoId(rs.getObject("payment_id", UUID.class)),
                new Money(rs.getLong("amount_cents"), rs.getString("currency")), rs.getString("gateway_transaction_id"),
                rs.getString("key"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
