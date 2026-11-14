package co.edu.corhuila.property.payment.adapter.in.http;

import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness probe (payment-service.yaml, getHealth). Outside /api/, so it needs no token, and
 * the gateway does not route it. A dependency that is down is reported inside the 200 (D-C6):
 * paymentGateway is the state of the circuit around Stripe (ADR-003), and status is degraded
 * while it is not ok.
 */
@RestController
public class HealthController {

    private final PasarelaPagoPort pasarela;

    public HealthController(PasarelaPagoPort pasarela) {
        this.pasarela = pasarela;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        String gateway = switch (pasarela.disponibilidad()) {
            case CAIDA -> "down";
            case DEGRADADA -> "degraded";
            case OK -> "ok";
        };
        return Map.of("status", "ok".equals(gateway) ? "ok" : "degraded",
                "timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                "dependencies", Map.of("paymentGateway", gateway));
    }
}
