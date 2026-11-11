package co.edu.corhuila.property.payment.adapter.out.broker;

import co.edu.corhuila.property.payment.application.port.out.EventPublisher;
import co.edu.corhuila.property.payment.application.port.out.PendingEvent;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Publishes to the exchange pagos of RabbitMQ as the user payment (ADR-013; Broker
 * topology in domain-events.md): persistent JSON messages whose messageId is the eventId,
 * with publisher confirms. The connection is opened on first use and opened again after
 * any failure, so a broker that comes back is used without restarting the relay.
 */
public class RabbitEventPublisher implements EventPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    private final ConnectionFactory factory;
    private final String exchange;
    private final long confirmTimeoutMs;
    private Connection connection;
    private Channel channel;

    public RabbitEventPublisher(String host, int port, String user, String password, String exchange,
                                Duration confirmTimeout) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("BROKER_PASSWORD is required: the relay publishes as its own broker user");
        }
        this.factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(user);
        factory.setPassword(password);
        factory.setConnectionTimeout((int) confirmTimeout.toMillis());
        factory.setAutomaticRecoveryEnabled(false);       // reopened here, on the next publish
        this.exchange = exchange;
        this.confirmTimeoutMs = confirmTimeout.toMillis();
    }

    @Override
    public synchronized void publish(String routingKey, PendingEvent event) {
        try {
            if (channel == null || !channel.isOpen()) {
                open();
            }
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .contentType("application/json").deliveryMode(2)
                    .messageId(event.id().toString()).type(event.eventType())
                    .correlationId(event.correlationId()).build();
            channel.basicPublish(exchange, routingKey, props, event.payload().getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(confirmTimeoutMs);
        } catch (Exception e) {
            close();
            throw new PublishException("the broker did not confirm " + event.id(), e);
        }
        // One line per event with its correlation id, never the payload.
        MDC.put("correlationId", event.correlationId());
        try {
            log.info("published eventType={} eventId={} routingKey={}", event.eventType(), event.id(), routingKey);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void open() throws Exception {
        close();
        connection = factory.newConnection("payment-relay");
        channel = connection.createChannel();
        channel.confirmSelect();
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception ignored) {
            // the connection is being discarded
        }
        connection = null;
        channel = null;
    }
}
