package co.edu.corhuila.property.payment.app;

import co.edu.corhuila.property.payment.adapter.in.http.AuthFilter;
import co.edu.corhuila.property.payment.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.property.payment.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.property.payment.adapter.out.pasarela.StripePasarela;
import co.edu.corhuila.property.payment.adapter.out.persistence.InMemoryPagoRepository;
import co.edu.corhuila.property.payment.adapter.out.persistence.JdbcPagoRepository;
import co.edu.corhuila.property.payment.application.port.out.PagoRepository;
import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort;
import co.edu.corhuila.property.payment.application.usecase.PagoService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Composition root: the only place that knows every concrete type. The filters
 * are registered here explicitly, in order: correlation first, so that even a
 * 401 carries its traceId.
 */
@Configuration
public class PaymentConfiguration {

    /**
     * PERSISTENCE=postgres (the default) needs DATABASE_URL, DATABASE_USER (payment_app
     * in the platform) and DATABASE_PASSWORD: without them the service does not start.
     * PERSISTENCE=memory is for tests and local development only (norm 5.5.2).
     */
    @Bean
    Storage storage(@Value("${payment.persistence:postgres}") String persistence,
                    @Value("${payment.database.url:}") String url,
                    @Value("${payment.database.user:}") String user,
                    @Value("${payment.database.password:}") String password,
                    @Value("${payment.database.pool-max:10}") int poolMax,
                    @Value("${payment.database.statement-timeout-ms:5000}") int statementTimeoutMs) {
        return Storage.connect(persistence, url, user, password, poolMax, statementTimeoutMs);
    }

    /**
     * Stripe in test mode, behind the Circuit Breaker (ADR-003). Every limit is
     * configuration with a default: 3 s per call, 8 s for the whole operation (shorter
     * than the workflow's 10 s for step cobrar), and the circuit opens after 5 failed
     * calls in a row and stays open 30 s. STRIPE_SECRET_KEY is required.
     */
    @Bean
    PasarelaPagoPort pasarela(@Value("${payment.pasarela.url}") String url,
                              @Value("${payment.pasarela.secret-key:}") String secretKey,
                              @Value("${payment.pasarela.timeout-ms}") long timeoutMs,
                              @Value("${payment.pasarela.total-ms}") long totalMs,
                              @Value("${payment.pasarela.max-attempts}") int maxAttempts,
                              @Value("${payment.pasarela.circuit.failures}") int failures,
                              @Value("${payment.pasarela.circuit.open-ms}") long openMs) {
        CircuitBreaker breaker = CircuitBreaker.of("pasarela", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(failures).minimumNumberOfCalls(failures).failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofMillis(openMs)).permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(false).build());
        return new StripePasarela(url, secretKey,
                new StripePasarela.Limits(Duration.ofMillis(timeoutMs), Duration.ofMillis(totalMs), maxAttempts), breaker);
    }

    @Bean
    PagoService pagoService(PagoRepository pagos, PasarelaPagoPort pasarela) {
        return new PagoService(pagos, pasarela);
    }

    @Bean
    PagoRepository pagoRepository(Storage storage) {
        return storage.inMemory() ? new InMemoryPagoRepository()
                : new JdbcPagoRepository(storage.jdbc(), storage.tx());
    }

    /**
     * JWT_PUBLIC_KEY (the PEM itself; a one-line value with literal \n escapes, as
     * a .env file holds it, is accepted) or JWT_PUBLIC_KEY_FILE. Without a key
     * the service does not start.
     */
    @Bean
    Rs256Verifier tokenVerifier(@Value("${JWT_PUBLIC_KEY:}") String pem,
                                @Value("${JWT_PUBLIC_KEY_FILE:}") String file) throws IOException {
        String key = !pem.isBlank() ? pem.replace("\\n", "\n")
                : !file.isBlank() ? Files.readString(Path.of(file)) : "";
        return new Rs256Verifier(key);
    }

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> bean = new FilterRegistrationBean<>(new CorrelationFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(Rs256Verifier verifier, ObjectMapper json) {
        FilterRegistrationBean<AuthFilter> bean = new FilterRegistrationBean<>(new AuthFilter(verifier, json));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
