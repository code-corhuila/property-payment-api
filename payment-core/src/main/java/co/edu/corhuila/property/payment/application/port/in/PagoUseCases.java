package co.edu.corhuila.property.payment.application.port.in;

import co.edu.corhuila.property.payment.domain.model.MetodoPago;
import co.edu.corhuila.property.payment.domain.model.Pago;
import co.edu.corhuila.property.payment.domain.model.Reembolso;
import java.util.UUID;

/** What the service offers about Pagos. Inbound adapters depend on these interfaces only. */
public final class PagoUseCases {

    private PagoUseCases() { }

    /**
     * E-20, step cobrar. The shape was already validated by the inbound adapter.
     * tokenPago is passed to the gateway and is never stored or logged.
     */
    public record CobrarCommand(Caller caller, UUID reservaId, UUID usuarioId, long montoCents, String moneda,
                                MetodoPago metodoPago, String tokenPago, String idempotencyKey, String correlationId) {

        /** A record prints its fields: this one never prints the card token. */
        @Override
        public String toString() {
            return "CobrarCommand[reservaId=" + reservaId + ", idempotencyKey=" + idempotencyKey + "]";
        }
    }

    /** created is false when the key was already used: the Pago already recorded, the gateway not called. */
    public record Cobro(Pago pago, boolean created) { }

    public interface Cobrar {
        Cobro cobrar(CobrarCommand command);
    }

    /** created is false when the Pago was already refunded, with this key or another. */
    public record Reversion(Reembolso reembolso, boolean created) { }

    /** E-21, compensation of step cobrar. */
    public interface Reembolsar {
        Reversion reembolsar(Caller caller, UUID pagoId, String idempotencyKey);
    }

    /** E-07, E-08 and E-22: the guest who paid, an ADMIN or a service credential. */
    public interface ConsultarPagos {
        Pago porId(Caller caller, UUID pagoId);

        Pago porReserva(Caller caller, UUID reservaId);

        Reembolso reembolso(Caller caller, UUID pagoId, UUID reembolsoId);
    }
}
