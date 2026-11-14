package co.edu.corhuila.property.payment.application.port.out;

import co.edu.corhuila.property.payment.domain.model.Pago;
import co.edu.corhuila.property.payment.domain.model.Reembolso;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The append-only ledger of the schema payment (06-data/models.md): a Pago and a
 * Reembolso are only ever inserted, never updated or deleted (AGGR-INV-004).
 */
public interface PagoRepository {

    /** created is false when the key, or the uniqueness of the ledger, returned what was already recorded. */
    record Created<T>(T value, boolean created) { }

    /**
     * Inserts the Pago, its Idempotency-Key and its event in one transaction. A key
     * already used returns its Pago with created=false.
     *
     * @throws PagoDuplicadoException when the Reserva already has a Pago created with another key
     */
    Created<Pago> createPago(Pago pago, List<OutboxEvent> events);

    Optional<Pago> findById(UUID id);

    Optional<Pago> findByKey(String idempotencyKey);

    Optional<Pago> findByReserva(UUID reservaId);

    /**
     * Inserts the Reembolso and its key in one transaction. Since a Pago is refunded at
     * most once (uq_refund_payment_id), a refund that lost a race returns the one
     * recorded, with created=false. A refund publishes no event in MVP 1.
     */
    Created<Reembolso> createReembolso(Reembolso reembolso);

    Optional<Reembolso> findReembolsoByPago(UUID pagoId);

    Optional<Reembolso> findReembolso(UUID id);
}
