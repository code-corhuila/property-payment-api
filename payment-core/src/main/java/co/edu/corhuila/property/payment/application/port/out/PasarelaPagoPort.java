package co.edu.corhuila.property.payment.application.port.out;

/**
 * The payment gateway (Stripe, test mode), behind a Circuit Breaker (ADR-003). The
 * Idempotency-Key of the step travels to the gateway as its own idempotency key, so a
 * retry never charges twice (HU-PAY-002, scenario 3). The card token is passed on and
 * never stored or logged.
 */
public interface PasarelaPagoPort {

    /** What a charge attempt ended in, as the table of E-20 distinguishes it. */
    sealed interface Cobro permits Aprobado, Rechazado, NoDisponible, Indeterminado { }

    /** The gateway approved the charge. */
    record Aprobado(String transaccionId) implements Cobro { }

    /** The gateway declined the card: FONDOS_INSUFICIENTES, TARJETA_RECHAZADA or TARJETA_VENCIDA. */
    record Rechazado(String motivoRechazo) implements Cobro { }

    /** The circuit is OPEN or the connection was refused before anything was sent: nothing was charged. */
    record NoDisponible() implements Cobro { }

    /** The charge was sent and asking again with the same key did not settle it: money may have moved. */
    record Indeterminado() implements Cobro { }

    /** What a refund attempt ended in. */
    sealed interface Reversion permits Reembolsado, Fallido { }

    record Reembolsado(String transaccionId) implements Reversion { }

    /** The gateway could not refund it now; nothing is recorded and the workflow retries. */
    record Fallido() implements Reversion { }

    Cobro cobrar(long montoCents, String moneda, String tokenPago, String idempotencyKey);

    /** Refunds the full amount of an approved charge (INV-006). */
    Reversion reembolsar(String transaccionPago, long montoCents, String idempotencyKey);
}
