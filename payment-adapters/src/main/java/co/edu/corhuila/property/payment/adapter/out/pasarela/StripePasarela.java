package co.edu.corhuila.property.payment.adapter.out.pasarela;

import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Stripe over plain HTTP (no SDK): POST /v1/payment_intents and POST /v1/refunds, with
 * the step's key as Stripe's Idempotency-Key. Every call goes through the Circuit
 * Breaker (ADR-003) and has its own time limit; the attempts of one operation fit in a
 * total budget shorter than the workflow's limit for the step (E-20). The request body
 * holds the card token: it is never logged, and no message built here includes it.
 */
public class StripePasarela implements PasarelaPagoPort {

    /** Settings, all from configuration with a default (application.yml). */
    public record Limits(Duration perCall, Duration total, int maxAttempts) { }

    private enum Outcome { ANSWERED, REFUSED, UNKNOWN }

    private record Reply(Outcome outcome, int status, JsonNode body) { }

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final URI baseUrl;
    private final String secretKey;
    private final Limits limits;
    private final CircuitBreaker breaker;

    public StripePasarela(String baseUrl, String secretKey, Limits limits, CircuitBreaker breaker) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("STRIPE_SECRET_KEY is required: the key of the Stripe test account, "
                    + "from the variables file of the environment, never versioned");
        }
        this.baseUrl = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.secretKey = secretKey;
        this.limits = limits;
        this.breaker = breaker;
        this.http = HttpClient.newBuilder().connectTimeout(limits.perCall())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Cobro cobrar(long montoCents, String moneda, String tokenPago, String idempotencyKey) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("amount", Long.toString(montoCents));
        form.put("currency", moneda.toLowerCase());
        form.put("confirm", "true");
        form.put("payment_method_types[]", "card");
        form.put("payment_method_data[type]", "card");
        form.put("payment_method_data[card][token]", tokenPago);
        boolean sent = false;   // once a charge may have reached Stripe, "nothing was charged" can no longer be said
        long deadline = System.nanoTime() + limits.total().toNanos();
        for (int attempt = 1; attempt <= limits.maxAttempts() && System.nanoTime() < deadline; attempt++) {
            Reply reply = post("v1/payment_intents", form, idempotencyKey);
            if (reply.outcome() == Outcome.REFUSED) {
                if (!sent) {
                    return new NoDisponible();
                }
                continue;
            }
            if (reply.outcome() == Outcome.UNKNOWN) {
                sent = true;
                continue;
            }
            int status = reply.status();
            if (status == 200 && "succeeded".equals(reply.body().path("status").asText())) {
                return new Aprobado(reply.body().path("id").asText());
            }
            if (status == 200 || status == 402 || status == 400) {
                return new Rechazado(motivo(reply.body().path("error")));
            }
            if ((status == 401 || status == 403) && !sent) {
                return new NoDisponible();     // the gateway refused our credential: it charged nothing
            }
            sent = true;                        // 409, 429, 5xx: the first attempt may still be running
        }
        return sent ? new Indeterminado() : new NoDisponible();
    }

    @Override
    public Reversion reembolsar(String transaccionPago, long montoCents, String idempotencyKey) {
        Map<String, String> form = Map.of("payment_intent", transaccionPago, "amount", Long.toString(montoCents));
        long deadline = System.nanoTime() + limits.total().toNanos();
        for (int attempt = 1; attempt <= limits.maxAttempts() && System.nanoTime() < deadline; attempt++) {
            Reply reply = post("v1/refunds", form, idempotencyKey);
            if (reply.outcome() == Outcome.ANSWERED && reply.status() == 200) {
                return new Reembolsado(reply.body().path("id").asText());
            }
            if (reply.outcome() == Outcome.ANSWERED && reply.status() < 500 && reply.status() != 409
                    && reply.status() != 429) {
                break;                          // a definitive answer: retrying changes nothing
            }
        }
        return new Fallido();
    }

    /** FONDOS_INSUFICIENTES, TARJETA_VENCIDA, or TARJETA_RECHAZADA for any other decline. */
    static String motivo(JsonNode error) {
        if ("insufficient_funds".equals(error.path("decline_code").asText())) {
            return "FONDOS_INSUFICIENTES";
        }
        if ("expired_card".equals(error.path("code").asText())
                || "expired_card".equals(error.path("decline_code").asText())) {
            return "TARJETA_VENCIDA";
        }
        return "TARJETA_RECHAZADA";
    }

    /**
     * One call through the breaker. OPEN, or a connection that could not be opened, is
     * REFUSED: nothing reached Stripe. A timeout after sending, or a reset, is UNKNOWN.
     */
    private Reply post(String path, Map<String, String> form, String idempotencyKey) {
        if (!breaker.tryAcquirePermission()) {
            return new Reply(Outcome.REFUSED, 0, null);
        }
        long start = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(limits.perCall())
                .header("Authorization", "Bearer " + secretKey).header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form))).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.nanoTime() - start;
            int status = response.statusCode();
            if (status >= 500 || status == 429) {
                breaker.onError(elapsed, TimeUnit.NANOSECONDS, new IOException("stripe answered " + status));
            } else {
                breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
            }
            return new Reply(Outcome.ANSWERED, status, read(response.body()));
        } catch (ConnectException | HttpConnectTimeoutException e) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            return new Reply(Outcome.REFUSED, 0, null);
        } catch (IOException e) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            return new Reply(Outcome.UNKNOWN, 0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            breaker.releasePermission();
            return new Reply(Outcome.UNKNOWN, 0, null);
        }
    }

    /** A body that is not JSON (a proxy's error page) reads as empty: its status decides. */
    private JsonNode read(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
