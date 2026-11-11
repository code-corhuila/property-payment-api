package co.edu.corhuila.property.payment.adapter.out.pasarela;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort.Aprobado;
import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort.Fallido;
import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort.Indeterminado;
import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort.NoDisponible;
import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort.Reembolsado;
import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort.Rechazado;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Stripe adapter against a fake Stripe: a real server on a free port with scripted answers. */
class StripePasarelaTest {

    record Answer(int status, String body, long delayMs) { }

    record Seen(String path, String key, String auth, String body) { }

    HttpServer server;
    final Deque<Answer> answers = new ArrayDeque<>();
    final List<Seen> seen = new ArrayList<>();
    CircuitBreaker breaker;
    StripePasarela stripe;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            synchronized (seen) {
                seen.add(new Seen(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            }
            Answer a = answers.size() > 1 ? answers.poll() : answers.peek();
            try {
                Thread.sleep(a.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = a.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(a.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        breaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom().slidingWindowSize(3).minimumNumberOfCalls(3)
                .failureRateThreshold(100).waitDurationInOpenState(Duration.ofMinutes(1)).build());
        stripe = new StripePasarela("http://127.0.0.1:" + server.getAddress().getPort(), "sk_test_123",
                new StripePasarela.Limits(Duration.ofMillis(300), Duration.ofSeconds(3), 2), breaker);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void answer(int status, String body) {
        answers.add(new Answer(status, body, 0));
    }

    static String declined(String code, String declineCode) {
        return "{\"error\":{\"type\":\"card_error\",\"code\":\"" + code + "\",\"decline_code\":\"" + declineCode + "\"}}";
    }

    @Test
    void anApprovedChargeCarriesTheKeyTheCredentialAndTheToken() {
        answer(200, "{\"id\":\"pi_123\",\"status\":\"succeeded\"}");

        var result = stripe.cobrar(157500000, "COP", "tok_visa", "saga-1:cobrar");
        assertEquals(new Aprobado("pi_123"), result);
        Seen s = seen.get(0);
        assertEquals("/v1/payment_intents", s.path());
        assertEquals("saga-1:cobrar", s.key());
        assertEquals("Bearer sk_test_123", s.auth());
        assertTrue(s.body().contains("amount=157500000"), s.body());
        assertTrue(s.body().contains("currency=cop"), s.body());
        assertTrue(s.body().contains("payment_method_data%5Bcard%5D%5Btoken%5D=tok_visa"), s.body());
    }

    @Test
    void theDeclinesOfTheGatewayAreMappedToTheThreeCodes() {
        answer(402, declined("card_declined", "insufficient_funds"));
        assertEquals(new Rechazado("FONDOS_INSUFICIENTES"), stripe.cobrar(100, "COP", "tok", "k-000001"));
        answers.clear();
        answer(402, declined("expired_card", ""));
        assertEquals(new Rechazado("TARJETA_VENCIDA"), stripe.cobrar(100, "COP", "tok", "k-000002"));
        answers.clear();
        answer(402, declined("card_declined", "generic_decline"));
        assertEquals(new Rechazado("TARJETA_RECHAZADA"), stripe.cobrar(100, "COP", "tok", "k-000003"));
    }

    @Test
    void aFailedFirstAttemptIsAskedAgainWithTheSameKey() {
        answer(500, "{}");
        answer(200, "{\"id\":\"pi_9\",\"status\":\"succeeded\"}");

        assertEquals(new Aprobado("pi_9"), stripe.cobrar(100, "COP", "tok", "saga-2:cobrar"));
        assertEquals(2, seen.size());
        assertEquals("saga-2:cobrar", seen.get(1).key());
    }

    @Test
    void whenTheChargeWasSentAndNothingSettlesItTheOutcomeIsUnknown() {
        answer(503, "<html>bad gateway</html>");
        assertInstanceOf(Indeterminado.class, stripe.cobrar(100, "COP", "tok", "k-000004"));

        answers.clear();
        answers.add(new Answer(200, "{\"id\":\"pi_late\",\"status\":\"succeeded\"}", 1000));
        assertInstanceOf(Indeterminado.class, stripe.cobrar(100, "COP", "tok", "k-000005"));
    }

    @Test
    void aGatewayThatCannotBeReachedChargedNothing() {
        server.stop(0);

        assertInstanceOf(NoDisponible.class, stripe.cobrar(100, "COP", "tok", "k-000006"));
    }

    @Test
    void anOpenCircuitSendsNothingAndAnswersNotAvailable() {
        answer(500, "{}");
        stripe.cobrar(100, "COP", "tok", "k-000007");
        stripe.cobrar(100, "COP", "tok", "k-000008");
        int calls = seen.size();

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertInstanceOf(NoDisponible.class, stripe.cobrar(100, "COP", "tok", "k-000009"));
        assertEquals(calls, seen.size(), "nothing reached the gateway");
    }

    @Test
    void aRefundGoesWithItsKeyAndAFailureRecordsNothing() {
        answer(200, "{\"id\":\"re_1\",\"status\":\"succeeded\"}");
        assertEquals(new Reembolsado("re_1"), stripe.reembolsar("pi_1", 157500000, "saga-1:cobrar:compensar"));
        assertEquals("/v1/refunds", seen.get(0).path());
        assertEquals("saga-1:cobrar:compensar", seen.get(0).key());
        assertTrue(seen.get(0).body().contains("payment_intent=pi_1"));

        answers.clear();
        answer(500, "{}");
        assertInstanceOf(Fallido.class, stripe.reembolsar("pi_1", 100, "k-000010"));
    }

    @Test
    void withoutASecretKeyTheServiceDoesNotStart() {
        var limits = new StripePasarela.Limits(Duration.ofSeconds(1), Duration.ofSeconds(2), 1);
        var e = assertThrows(IllegalStateException.class, () -> new StripePasarela("http://x", " ", limits, breaker));
        assertTrue(e.getMessage().contains("STRIPE_SECRET_KEY"));
    }
}
