package co.edu.corhuila.property.payment.app;

import co.edu.corhuila.property.payment.adapter.in.relay.OutboxRelayLoop;
import co.edu.corhuila.property.payment.adapter.out.broker.RabbitEventPublisher;
import co.edu.corhuila.property.payment.adapter.out.persistence.JdbcOutboxStore;
import co.edu.corhuila.property.payment.application.usecase.RelayOutbox;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The composition root of the outbox relay: the same jar, started with --outbox-relay, as a
 * process apart from the API (ADR-005; norm 5.3.11). No web server, no controllers, no token.
 * It is deliberately not a @Configuration, so the component scan of the API never loads it.
 */
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
public class OutboxRelayApplication {

    /** The relay reads and marks the outbox, so the database is required, as for the API. */
    @Bean
    Storage storage(@Value("${payment.database.url:}") String url,
                    @Value("${payment.database.user:}") String user,
                    @Value("${payment.database.password:}") String password,
                    @Value("${payment.relay.pool-max:2}") int poolMax,
                    @Value("${payment.database.statement-timeout-ms:5000}") int statementTimeoutMs) {
        return Storage.connect("postgres", url, user, password, poolMax, statementTimeoutMs);
    }

    @Bean(destroyMethod = "close")
    RabbitEventPublisher publisher(@Value("${payment.broker.host}") String host,
                                   @Value("${payment.broker.port}") int port,
                                   @Value("${payment.broker.user}") String user,
                                   @Value("${payment.broker.password:}") String password,
                                   @Value("${payment.broker.confirm-timeout-ms}") long confirmTimeoutMs) {
        return new RabbitEventPublisher(host, port, user, password, "pagos", Duration.ofMillis(confirmTimeoutMs));
    }

    @Bean
    OutboxRelayLoop relayLoop(Storage storage, RabbitEventPublisher publisher,
                              @Value("${payment.relay.batch-size}") int batchSize,
                              @Value("${payment.relay.poll-ms}") long pollMs) {
        return new OutboxRelayLoop(new RelayOutbox(new JdbcOutboxStore(storage.jdbc()), publisher, batchSize),
                Duration.ofMillis(pollMs));
    }

    @Bean
    ApplicationRunner runRelay(OutboxRelayLoop loop) {
        Runtime.getRuntime().addShutdownHook(new Thread(loop::stop));
        return args -> loop.run();
    }
}
