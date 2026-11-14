package co.edu.corhuila.property.payment.application.port.in;

/** No Pago, or no Reembolso, with that id: 404 NOT_FOUND. */
public class PagoNoEncontradoException extends RuntimeException {
    public PagoNoEncontradoException(String message) {
        super(message);
    }
}
