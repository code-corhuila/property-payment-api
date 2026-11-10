package co.edu.corhuila.property.payment.adapter.out.persistence;

import co.edu.corhuila.property.payment.application.port.out.OutboxEvent;
import co.edu.corhuila.property.payment.application.port.out.PagoDuplicadoException;
import co.edu.corhuila.property.payment.application.port.out.PagoRepository;
import co.edu.corhuila.property.payment.domain.model.Pago;
import co.edu.corhuila.property.payment.domain.model.Reembolso;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Only with PERSISTENCE=memory, for tests and local development: never a default
 * (norm 5.5.2). Same contract as the JDBC adapter. A Pago and a Reembolso are
 * immutable, so the stored objects can be handed out as they are.
 */
public class InMemoryPagoRepository implements PagoRepository {

    private final Map<UUID, Pago> pagos = new HashMap<>();
    private final Map<UUID, Reembolso> reembolsos = new HashMap<>();
    private final Map<String, UUID> keys = new HashMap<>();
    private final List<OutboxEvent> outbox = new ArrayList<>();

    @Override
    public synchronized Created<Pago> createPago(Pago pago, List<OutboxEvent> events) {
        Optional<Pago> first = findByKey(pago.getIdempotencyKey());
        if (first.isPresent()) {
            return new Created<>(first.get(), false);
        }
        if (findByReserva(pago.getReservaId()).isPresent()) {
            throw new PagoDuplicadoException();
        }
        pagos.put(pago.getId().value(), pago);
        keys.put(pago.getIdempotencyKey(), pago.getId().value());
        outbox.addAll(events);
        return new Created<>(pago, true);
    }

    @Override
    public synchronized Optional<Pago> findById(UUID id) {
        return Optional.ofNullable(pagos.get(id));
    }

    @Override
    public synchronized Optional<Pago> findByKey(String idempotencyKey) {
        return Optional.ofNullable(keys.get(idempotencyKey)).map(pagos::get);
    }

    @Override
    public synchronized Optional<Pago> findByReserva(UUID reservaId) {
        return pagos.values().stream().filter(p -> p.getReservaId().equals(reservaId)).findFirst();
    }

    @Override
    public synchronized Created<Reembolso> createReembolso(Reembolso reembolso) {
        Optional<Reembolso> first = findReembolsoByPago(reembolso.getPagoId().value());
        if (first.isPresent()) {
            return new Created<>(first.get(), false);
        }
        reembolsos.put(reembolso.getId().value(), reembolso);
        keys.put(reembolso.getIdempotencyKey(), reembolso.getId().value());
        return new Created<>(reembolso, true);
    }

    @Override
    public synchronized Optional<Reembolso> findReembolsoByPago(UUID pagoId) {
        return reembolsos.values().stream().filter(r -> r.getPagoId().value().equals(pagoId)).findFirst();
    }

    @Override
    public synchronized Optional<Reembolso> findReembolso(UUID id) {
        return Optional.ofNullable(reembolsos.get(id));
    }

    /** The events written so far, oldest first: what the outbox table would hold. */
    public synchronized List<OutboxEvent> outbox() {
        return List.copyOf(outbox);
    }
}
