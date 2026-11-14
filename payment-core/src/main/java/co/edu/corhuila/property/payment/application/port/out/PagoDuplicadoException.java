package co.edu.corhuila.property.payment.application.port.out;

import co.edu.corhuila.property.payment.domain.model.DomainException;

/** E-20: the Reserva already has a Pago created with another key; a Reserva is charged once (422). */
public class PagoDuplicadoException extends DomainException {
    public PagoDuplicadoException() {
        super("the reservation already has a payment created with another key");
    }
}
