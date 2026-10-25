package co.edu.corhuila.property.payment.domain.model;

/** Outcome of a charge attempt. A Pago is created already resolved: both states are terminal. */
public enum EstadoPago {
    APROBADO,
    RECHAZADO
}
