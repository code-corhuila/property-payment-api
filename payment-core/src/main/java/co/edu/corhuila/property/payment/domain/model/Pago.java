package co.edu.corhuila.property.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root of the payment domain (02-domain/entities-and-rules.md): one
 * charge attempt tied to a Reserva, created already resolved and immutable once
 * persisted (AGGR-INV-004). It is created only by step cobrar of the Saga.
 */
public class Pago {

    private final PagoId id;
    private final UUID reservaId;
    private final UUID usuarioId;
    private final Money monto;
    private final MetodoPago metodoPago;
    private final EstadoPago estado;
    private final String transaccionId;
    private final String motivoRechazo;
    private final String idempotencyKey;
    private final Instant createdAt;

    private Pago(PagoId id, UUID reservaId, UUID usuarioId, Money monto, MetodoPago metodoPago,
                  EstadoPago estado, String transaccionId, String motivoRechazo,
                  String idempotencyKey) {
        if (reservaId == null || usuarioId == null || monto == null || metodoPago == null) {
            throw new DomainException("reservaId, usuarioId, monto y metodoPago son requeridos");
        }
        // INV-005: the amount is the total of the Reserva, and it is greater than zero.
        if (monto.esMenorOIgualACero()) {
            throw new DomainException("INV-005: El monto del pago debe ser mayor a cero");
        }
        // INV-004: every Pago carries the key of the request that created it.
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new DomainException("INV-004: La idempotencyKey es requerida, de 8 a 128 caracteres");
        }
        this.id = id;
        this.reservaId = reservaId;
        this.usuarioId = usuarioId;
        this.monto = monto;
        this.metodoPago = metodoPago;
        this.estado = estado;
        this.transaccionId = transaccionId;
        this.motivoRechazo = motivoRechazo;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
    }

    public static Pago aprobado(UUID reservaId, UUID usuarioId, Money monto, MetodoPago metodoPago,
                                 String transaccionId, String idempotencyKey) {
        if (transaccionId == null || transaccionId.isBlank()) {
            throw new DomainException("Un pago APROBADO requiere el transaccionId de la pasarela");
        }
        return new Pago(PagoId.nueva(), reservaId, usuarioId, monto, metodoPago,
                EstadoPago.APROBADO, transaccionId, null, idempotencyKey);
    }

    public static Pago rechazado(UUID reservaId, UUID usuarioId, Money monto, MetodoPago metodoPago,
                                  String motivoRechazo, String idempotencyKey) {
        if (motivoRechazo == null || motivoRechazo.isBlank()) {
            throw new DomainException("Un pago RECHAZADO requiere su motivoRechazo");
        }
        return new Pago(PagoId.nueva(), reservaId, usuarioId, monto, metodoPago,
                EstadoPago.RECHAZADO, null, motivoRechazo, idempotencyKey);
    }

    public boolean fueAprobado() {
        return this.estado == EstadoPago.APROBADO;
    }

    public PagoId getId() { return id; }
    public UUID getReservaId() { return reservaId; }
    public UUID getUsuarioId() { return usuarioId; }
    public Money getMonto() { return monto; }
    public MetodoPago getMetodoPago() { return metodoPago; }
    public EstadoPago getEstado() { return estado; }
    public String getTransaccionId() { return transaccionId; }
    public String getMotivoRechazo() { return motivoRechazo; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
