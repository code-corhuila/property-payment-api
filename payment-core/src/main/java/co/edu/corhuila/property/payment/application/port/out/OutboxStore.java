package co.edu.corhuila.property.payment.application.port.out;

import java.util.List;
import java.util.UUID;

/** The outbox as the relay sees it (ADR-005): the rows to publish, in order, and their mark. */
public interface OutboxStore {

    /** At most {@code max} unpublished rows, oldest first. */
    List<PendingEvent> nextUnpublished(int max);

    /** Marks the row published. Called only after the broker confirmed the message. */
    void markPublished(UUID id);
}
