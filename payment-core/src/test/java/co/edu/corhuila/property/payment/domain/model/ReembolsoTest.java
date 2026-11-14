package co.edu.corhuila.property.payment.domain.model;

import static co.edu.corhuila.property.payment.domain.model.PagoTest.HUESPED;
import static co.edu.corhuila.property.payment.domain.model.PagoTest.RESERVA;
import static co.edu.corhuila.property.payment.domain.model.PagoTest.TOTAL;
import static co.edu.corhuila.property.payment.domain.model.PagoTest.aprobado;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ReembolsoTest {

    @Test
    void inv006AnApprovedPagoIsRefundedInFull() {
        Pago pago = aprobado();
        Reembolso reembolso = Reembolso.crear(pago, "txn_r_0001", "saga-0001:cobrar:compensar");

        assertEquals(pago.getId(), reembolso.getPagoId());
        assertEquals(pago.getMonto(), reembolso.getMonto());
        assertEquals("txn_r_0001", reembolso.getTransaccionId());
    }

    @Test
    void inv006ARejectedPagoCannotBeRefunded() {
        Pago rechazado = Pago.rechazado(RESERVA, HUESPED, TOTAL, MetodoPago.TARJETA_CREDITO,
                "TARJETA_RECHAZADA", "saga-0001:cobrar");

        assertThrows(DomainException.class,
                () -> Reembolso.crear(rechazado, "txn_r_0001", "saga-0001:cobrar:compensar"));
    }

    @Test
    void requiresTheGatewayReferenceAndAValidKey() {
        assertThrows(DomainException.class, () -> Reembolso.crear(aprobado(), " ", "saga-0001:cobrar:compensar"));
        assertThrows(DomainException.class, () -> Reembolso.crear(aprobado(), "txn_r_0001", "short"));
        assertThrows(DomainException.class, () -> Reembolso.crear(null, "txn_r_0001", "saga-0001:cobrar:compensar"));
    }
}
