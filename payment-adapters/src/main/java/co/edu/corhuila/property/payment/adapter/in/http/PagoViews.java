package co.edu.corhuila.property.payment.adapter.in.http;

import co.edu.corhuila.property.payment.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.property.payment.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.property.payment.application.port.in.Caller;
import co.edu.corhuila.property.payment.domain.model.Pago;
import co.edu.corhuila.property.payment.domain.model.Reembolso;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** The public shapes of payment-service.yaml, and what every controller reads from a request. */
final class PagoViews {

    static final Pattern UUID_FORMAT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private PagoViews() { }

    /** PagoResponse: created already resolved, so it has no updatedAt. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PagoResponse(String id, String reservaId, String usuarioId, long montoCents, String moneda, String metodoPago,
                        String estado, String transaccionId, String motivoRechazo, String createdAt) {
        static PagoResponse of(Pago p) {
            return new PagoResponse(p.getId().value().toString(), p.getReservaId().toString(), p.getUsuarioId().toString(),
                    p.getMonto().getMontoCents(), p.getMonto().getMoneda(), p.getMetodoPago().name(), p.getEstado().name(),
                    p.getTransaccionId(), p.getMotivoRechazo(), p.getCreatedAt().truncatedTo(ChronoUnit.SECONDS).toString());
        }
    }

    /** ReembolsoResponse: always the full amount of the Pago (INV-006). */
    record ReembolsoResponse(String id, String pagoId, long montoCents, String moneda, String transaccionId,
                             String createdAt) {
        static ReembolsoResponse of(Reembolso r) {
            return new ReembolsoResponse(r.getId().value().toString(), r.getPagoId().value().toString(),
                    r.getMonto().getMontoCents(), r.getMonto().getMoneda(), r.getTransaccionId(),
                    r.getCreatedAt().truncatedTo(ChronoUnit.SECONDS).toString());
        }
    }

    /** The caller of a protected route, from the claims AuthFilter verified. */
    static Caller caller(HttpServletRequest request) {
        JsonNode claims = (JsonNode) request.getAttribute(AuthFilter.CLAIMS_ATTRIBUTE);
        List<String> roles = new ArrayList<>();
        claims.path("roles").forEach(r -> roles.add(r.asText()));
        return new Caller(claims.path("sub").asText(), roles, "service".equals(claims.path("scope").asText()));
    }

    /** The X-Correlation-Id of the request (CorrelationFilter put it in the MDC). */
    static String correlationId() {
        return MDC.get(CorrelationFilter.MDC_KEY);
    }

    static UUID requireId(String value, String field) {
        if (value == null || !UUID_FORMAT.matcher(value).matches()) {
            throw new ValidationException("the request has invalid fields", List.of(new FieldError(field, "must be a UUID")));
        }
        return UUID.fromString(value);
    }

    /** Idempotency-Key: required on every creation, 8 to 128 characters (D-C7). */
    static boolean validKey(String key) {
        return key != null && key.length() >= 8 && key.length() <= 128;
    }
}
