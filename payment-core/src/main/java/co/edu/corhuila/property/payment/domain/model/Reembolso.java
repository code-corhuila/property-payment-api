package co.edu.corhuila.property.payment.domain.model;

import java.time.Instant;

/**
 * A ledger entry that reverses an approved Pago (02-domain/entities-and-rules.md):
 * the compensation of step cobrar. The Pago it reverses is never edited; the
 * refund is a new, immutable entry.
 */
public class Reembolso {

    private final ReembolsoId id;
    private final PagoId pagoId;
    private final Money monto;
    private final String transaccionId;
    private final String idempotencyKey;
    private final Instant createdAt;

    private Reembolso(ReembolsoId id, PagoId pagoId, Money monto, String transaccionId,
                      String idempotencyKey) {
        this.id = id;
        this.pagoId = pagoId;
        this.monto = monto;
        this.transaccionId = transaccionId;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
    }

    /**
     * INV-006: only a Pago in APROBADO is refunded, and for its full amount. That
     * it happens at most once per Pago is enforced by uq_refund_payment_id.
     */
    public static Reembolso crear(Pago pago, String transaccionId, String idempotencyKey) {
        if (pago == null) {
            throw new DomainException("El pago a reembolsar es requerido");
        }
        if (!pago.fueAprobado()) {
            throw new DomainException("INV-006: Solo se reembolsa un pago APROBADO; este está " + pago.getEstado());
        }
        if (transaccionId == null || transaccionId.isBlank()) {
            throw new DomainException("Un reembolso requiere el transaccionId de la pasarela");
        }
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new DomainException("La idempotencyKey es requerida, de 8 a 128 caracteres");
        }
        return new Reembolso(ReembolsoId.nueva(), pago.getId(), pago.getMonto(), transaccionId, idempotencyKey);
    }

    public ReembolsoId getId() { return id; }
    public PagoId getPagoId() { return pagoId; }
    public Money getMonto() { return monto; }
    public String getTransaccionId() { return transaccionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
