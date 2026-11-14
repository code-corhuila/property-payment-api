package co.edu.corhuila.property.payment.application.usecase;

import co.edu.corhuila.property.payment.application.port.out.OutboxEvent;
import co.edu.corhuila.property.payment.domain.model.Pago;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The envelope of PagoAprobado or PagoRechazado (02-domain/domain-events.md). Every Pago
 * raises exactly one of them, when it is recorded. metadata.userId is the guest who
 * pays, never the sub of the service token (D-C14); causationId is null.
 */
public final class PagoEvents {

    private PagoEvents() { }

    public static OutboxEvent of(Pago pago, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pagoId", pago.getId().value().toString());
        payload.put("reservaId", pago.getReservaId().toString());
        payload.put("montoCents", pago.getMonto().getMontoCents());
        payload.put("moneda", pago.getMonto().getMoneda());
        String type;
        if (pago.fueAprobado()) {
            type = "PagoAprobado";
            payload.put("metodoPago", pago.getMetodoPago().name());
            payload.put("transaccionId", pago.getTransaccionId());
        } else {
            type = "PagoRechazado";
            payload.put("motivoRechazo", pago.getMotivoRechazo());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("correlationId", correlationId);
        metadata.put("causationId", null);
        metadata.put("userId", pago.getUsuarioId().toString());

        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", type);
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateId", pago.getId().value().toString());
        envelope.put("aggregateType", "Pago");
        envelope.put("occurredAt", pago.getCreatedAt().toString());
        envelope.put("version", 1);
        envelope.put("payload", payload);
        envelope.put("metadata", metadata);
        return new OutboxEvent(eventId, pago.getId().value(), type, envelope);
    }
}
