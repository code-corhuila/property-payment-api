package co.edu.corhuila.property.payment.application.port.out;

/** The broker as the relay sees it (ADR-013). */
public interface EventPublisher {

    /**
     * Publishes the event with that routing key as a persistent message, and returns only
     * when the broker confirmed it. Any failure, including no confirm in time, throws.
     */
    void publish(String routingKey, PendingEvent event);

    /** The broker could not take the message: the row stays unpublished and is tried again. */
    class PublishException extends RuntimeException {
        public PublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
