package co.edu.corhuila.property.payment.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.payment.application.port.out.PagoDuplicadoException;
import co.edu.corhuila.property.payment.application.usecase.PagoEvents;
import co.edu.corhuila.property.payment.domain.model.MetodoPago;
import co.edu.corhuila.property.payment.domain.model.Money;
import co.edu.corhuila.property.payment.domain.model.Pago;
import co.edu.corhuila.property.payment.domain.model.Reembolso;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The in-memory twin keeps the contract of the port, as the JDBC adapter does. */
class InMemoryPagoRepositoryTest {

    final InMemoryPagoRepository repository = new InMemoryPagoRepository();

    static Pago aprobado(UUID reserva, String key) {
        return Pago.aprobado(reserva, UUID.randomUUID(), new Money(45000, "COP"), MetodoPago.TARJETA_CREDITO, "txn_1", key);
    }

    @Test
    void storesThePagoItsKeyAndItsEvent() {
        Pago pago = aprobado(UUID.randomUUID(), "saga-1:cobrar");
        assertTrue(repository.createPago(pago, List.of(PagoEvents.of(pago, "t"))).created());

        assertEquals(pago, repository.findById(pago.getId().value()).orElseThrow());
        assertEquals(pago, repository.findByKey("saga-1:cobrar").orElseThrow());
        assertEquals(pago, repository.findByReserva(pago.getReservaId()).orElseThrow());
        assertEquals("PagoAprobado", repository.outbox().get(0).eventType());
    }

    @Test
    void aRepeatedKeyReturnsTheFirstPagoAndASecondKeyForTheSameReservaIsRefused() {
        UUID reserva = UUID.randomUUID();
        Pago first = aprobado(reserva, "saga-1:cobrar");
        repository.createPago(first, List.of());

        var retry = repository.createPago(aprobado(UUID.randomUUID(), "saga-1:cobrar"), List.of());
        assertFalse(retry.created());
        assertEquals(first, retry.value());
        assertThrows(PagoDuplicadoException.class, () -> repository.createPago(aprobado(reserva, "saga-2:cobrar"), List.of()));
    }

    @Test
    void aPagoIsRefundedOnce() {
        Pago pago = aprobado(UUID.randomUUID(), "saga-1:cobrar");
        repository.createPago(pago, List.of());
        Reembolso first = Reembolso.crear(pago, "re_1", "saga-1:cobrar:compensar");

        assertTrue(repository.createReembolso(first).created());
        var again = repository.createReembolso(Reembolso.crear(pago, "re_2", "otra-clave-00001"));
        assertFalse(again.created());
        assertEquals(first, again.value());
        assertEquals(first, repository.findReembolsoByPago(pago.getId().value()).orElseThrow());
        assertEquals(first, repository.findReembolso(first.getId().value()).orElseThrow());
    }
}
