package co.edu.corhuila.property.payment.application.port.in;

/**
 * E-20, third row of the table: the charge was sent and asking again with the same key
 * did not settle it. No Pago is recorded and the answer is 500, never
 * PASARELA_NO_DISPONIBLE: money may have moved. The workflow retries with the same key.
 */
public class CobroIndeterminadoException extends RuntimeException {
    public CobroIndeterminadoException() {
        super("the outcome of the charge is unknown: nothing was recorded");
    }
}
