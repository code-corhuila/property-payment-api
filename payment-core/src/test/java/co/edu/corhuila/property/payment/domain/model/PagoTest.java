package co.edu.corhuila.property.payment.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PagoTest {

    static final UUID RESERVA = UUID.fromString("55555555-5555-4555-8555-555555555555");
    static final UUID HUESPED = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final Money TOTAL = new Money(450000, "COP");

    static Pago aprobado() {
        return Pago.aprobado(RESERVA, HUESPED, TOTAL, MetodoPago.TARJETA_CREDITO, "txn_0001", "saga-0001:cobrar");
    }

    @Test
    void anApprovedChargeIsAPagoAprobadoWithItsTransaction() {
        Pago pago = aprobado();

        assertTrue(pago.fueAprobado());
        assertEquals(EstadoPago.APROBADO, pago.getEstado());
        assertEquals("txn_0001", pago.getTransaccionId());
        assertNull(pago.getMotivoRechazo());
        assertEquals(TOTAL, pago.getMonto());
    }

    @Test
    void aDeclinedChargeIsAPagoRechazadoWithItsMotivo() {
        Pago pago = Pago.rechazado(RESERVA, HUESPED, TOTAL, MetodoPago.TARJETA_DEBITO,
                "FONDOS_INSUFICIENTES", "saga-0001:cobrar");

        assertFalse(pago.fueAprobado());
        assertEquals(EstadoPago.RECHAZADO, pago.getEstado());
        assertEquals("FONDOS_INSUFICIENTES", pago.getMotivoRechazo());
        assertNull(pago.getTransaccionId());
    }

    @Test
    void inv005TheAmountMustBePositive() {
        assertThrows(DomainException.class, () -> Pago.aprobado(RESERVA, HUESPED, new Money(0, "COP"),
                MetodoPago.TARJETA_CREDITO, "txn_0001", "saga-0001:cobrar"));
    }

    @Test
    void inv004EveryPagoCarriesAValidIdempotencyKey() {
        assertThrows(DomainException.class, () -> Pago.aprobado(RESERVA, HUESPED, TOTAL,
                MetodoPago.TARJETA_CREDITO, "txn_0001", null));
        assertThrows(DomainException.class, () -> Pago.aprobado(RESERVA, HUESPED, TOTAL,
                MetodoPago.TARJETA_CREDITO, "txn_0001", "short"));
        assertThrows(DomainException.class, () -> Pago.aprobado(RESERVA, HUESPED, TOTAL,
                MetodoPago.TARJETA_CREDITO, "txn_0001", "k".repeat(129)));
    }

    @Test
    void theOutcomeIsConsistentWithTheStatus() {
        assertThrows(DomainException.class, () -> Pago.aprobado(RESERVA, HUESPED, TOTAL,
                MetodoPago.TARJETA_CREDITO, " ", "saga-0001:cobrar"));
        assertThrows(DomainException.class, () -> Pago.rechazado(RESERVA, HUESPED, TOTAL,
                MetodoPago.TARJETA_CREDITO, null, "saga-0001:cobrar"));
    }

    @Test
    void requiresTheReservaTheGuestAndTheMethod() {
        assertThrows(DomainException.class, () -> Pago.aprobado(null, HUESPED, TOTAL,
                MetodoPago.TARJETA_CREDITO, "txn_0001", "saga-0001:cobrar"));
        assertThrows(DomainException.class, () -> Pago.aprobado(RESERVA, HUESPED, TOTAL,
                null, "txn_0001", "saga-0001:cobrar"));
    }
}
