package co.edu.corhuila.property.payment.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import co.edu.corhuila.property.payment.application.port.out.OutboxEvent;
import co.edu.corhuila.property.payment.domain.model.MetodoPago;
import co.edu.corhuila.property.payment.domain.model.Money;
import co.edu.corhuila.property.payment.domain.model.Pago;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PagoAprobado and PagoRechazado as 02-domain/domain-events.md defines them. */
class PagoEventsTest {

    static final UUID RESERVA = UUID.randomUUID();
    static final UUID HUESPED = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    static Map<String, Object> part(OutboxEvent e, String name) {
        return (Map<String, Object>) e.envelope().get(name);
    }

    @Test
    void anApprovedPagoRaisesPagoAprobado() {
        Pago pago = Pago.aprobado(RESERVA, HUESPED, new Money(45000, "COP"), MetodoPago.TARJETA_CREDITO, "txn_8827af1",
                "saga-1:cobrar");
        OutboxEvent e = PagoEvents.of(pago, "saga-trace");

        assertEquals("PagoAprobado", e.eventType());
        assertEquals(pago.getId().value(), e.aggregateId());
        assertEquals("Pago", e.envelope().get("aggregateType"));
        assertEquals(pago.getCreatedAt().toString(), e.envelope().get("occurredAt"));
        assertEquals(Map.of("pagoId", pago.getId().value().toString(), "reservaId", RESERVA.toString(),
                "montoCents", 45000L, "moneda", "COP", "metodoPago", "TARJETA_CREDITO", "transaccionId", "txn_8827af1"),
                part(e, "payload"));
        assertEquals("saga-trace", part(e, "metadata").get("correlationId"));
        assertNull(part(e, "metadata").get("causationId"));
        assertEquals(HUESPED.toString(), part(e, "metadata").get("userId"));
    }

    @Test
    void aRejectedPagoRaisesPagoRechazadoWithItsMotivo() {
        Pago pago = Pago.rechazado(RESERVA, HUESPED, new Money(45000, "COP"), MetodoPago.TARJETA_DEBITO,
                "FONDOS_INSUFICIENTES", "saga-1:cobrar");
        OutboxEvent e = PagoEvents.of(pago, "t");

        assertEquals("PagoRechazado", e.eventType());
        assertEquals("FONDOS_INSUFICIENTES", part(e, "payload").get("motivoRechazo"));
        assertFalse(part(e, "payload").containsKey("transaccionId"));
    }
}
