package co.edu.corhuila.property.payment.application.port.out;

import java.util.Map;
import java.util.UUID;

/**
 * One row of payment.outbox_event (ADR-005): the full envelope of a domain event, as
 * 02-domain/domain-events.md defines it. It is written in the same transaction as the
 * Pago it reports; a relay publishes it later (tanda 3).
 */
public record OutboxEvent(UUID id, UUID aggregateId, String eventType, Map<String, Object> envelope) { }
