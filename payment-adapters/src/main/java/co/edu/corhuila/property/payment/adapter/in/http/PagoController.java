package co.edu.corhuila.property.payment.adapter.in.http;

import co.edu.corhuila.property.payment.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.property.payment.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.property.payment.adapter.in.http.PagoViews.PagoResponse;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases.CobrarCommand;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases.Cobro;
import co.edu.corhuila.property.payment.domain.model.MetodoPago;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The ledger over HTTP. Validates the SHAPE of the input and holds no rule; never logs a body. */
@RestController
@RequestMapping("/api/v1/pagos")
public class PagoController {

    /** montoCents is read as BigDecimal so that 12.5 is rejected instead of silently truncated (D-C8). */
    public record CrearPagoRequest(String reservaId, String usuarioId, BigDecimal montoCents, String moneda,
                                   String metodoPago, String tokenPago) {
        /** A record prints its fields: this one never prints the card token. */
        @Override
        public String toString() {
            return "CrearPagoRequest[reservaId=" + reservaId + "]";
        }
    }

    private final PagoUseCases.Cobrar cobrar;

    public PagoController(PagoUseCases.Cobrar cobrar) {
        this.cobrar = cobrar;
    }

    /** E-20, step cobrar. A rejected charge is 201 too: it is a recorded result. */
    @PostMapping
    public ResponseEntity<PagoResponse> cobrar(@RequestBody CrearPagoRequest body,
                                               @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                               HttpServletRequest request) {
        List<FieldError> problems = new ArrayList<>();
        UUID reservaId = uuid(body.reservaId(), "reservaId", problems);
        UUID usuarioId = uuid(body.usuarioId(), "usuarioId", problems);
        BigDecimal monto = body.montoCents();
        if (monto == null || monto.signum() <= 0 || monto.stripTrailingZeros().scale() > 0
                || monto.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            problems.add(new FieldError("montoCents", "must be a positive integer (minor units)"));
        }
        if (!"COP".equals(body.moneda())) {
            problems.add(new FieldError("moneda", "must be COP"));
        }
        MetodoPago metodo = null;
        try {
            metodo = MetodoPago.valueOf(String.valueOf(body.metodoPago()));
        } catch (IllegalArgumentException e) {
            problems.add(new FieldError("metodoPago", "must be TARJETA_CREDITO or TARJETA_DEBITO"));
        }
        if (body.tokenPago() == null || body.tokenPago().isBlank() || body.tokenPago().length() > 255) {
            problems.add(new FieldError("tokenPago", "required: the card token of the gateway"));
        }
        if (!PagoViews.validKey(key)) {
            problems.add(new FieldError("Idempotency-Key", "header required, 8 to 128 characters"));
        }
        if (!problems.isEmpty()) {
            throw new ValidationException("the request has invalid fields", problems);
        }
        Cobro result = cobrar.cobrar(new CobrarCommand(PagoViews.caller(request), reservaId, usuarioId,
                monto.longValueExact(), body.moneda(), metodo, body.tokenPago(), key, PagoViews.correlationId()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)   // 200: a retry
                .header("Location", "/api/v1/pagos/" + result.pago().getId().value())
                .body(PagoResponse.of(result.pago()));
    }

    private static UUID uuid(String value, String field, List<FieldError> problems) {
        if (value == null || !PagoViews.UUID_FORMAT.matcher(value).matches()) {
            problems.add(new FieldError(field, "must be a UUID"));
            return null;
        }
        return UUID.fromString(value);
    }
}
