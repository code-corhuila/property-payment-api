package co.edu.corhuila.property.payment.app;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.property.payment.adapter.out.persistence.InMemoryPagoRepository;
import co.edu.corhuila.property.payment.application.port.out.PagoRepository;
import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HU-PAY-001 and HU-PAY-002 over HTTP (E-20), with the gateway simulated behind its port. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class CobrarHttpTest {

    static final String SERVICE = TestKeys.bearer("\"sub\":\"svc-workflow\",\"scope\":\"service\"");
    static final String TOKEN = "tok_card_secret_7f3a9c";

    @Autowired
    MockMvc mvc;
    @Autowired
    PagoRepository pagos;
    @MockitoBean
    PasarelaPagoPort pasarela;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    ResultActions cobrar(UUID reserva, String key, String bearer) throws Exception {
        String body = "{\"reservaId\":\"" + reserva + "\",\"usuarioId\":\"c8f2a1b0-4444-4c3e-9a2d-000000000004\","
                + "\"montoCents\":157500000,\"moneda\":\"COP\",\"metodoPago\":\"TARJETA_CREDITO\",\"tokenPago\":\"" + TOKEN + "\"}";
        return mvc.perform(post("/api/v1/pagos").header("Authorization", bearer).header("Idempotency-Key", key)
                .header("X-Correlation-Id", "saga-trace-9").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    void gatewayAnswers(PasarelaPagoPort.Cobro outcome) {
        when(pasarela.cobrar(anyLong(), anyString(), anyString(), anyString())).thenReturn(outcome);
    }

    String eventOf(UUID reserva) {
        return ((InMemoryPagoRepository) pagos).outbox().stream()
                .filter(e -> pagos.findByReserva(reserva).orElseThrow().getId().value().equals(e.aggregateId()))
                .findFirst().orElseThrow().eventType();
    }

    @Test
    void pay001Scenario1AnApprovedChargeIs201WithItsLedgerEntryAndPagoAprobado() throws Exception {
        gatewayAnswers(new PasarelaPagoPort.Aprobado("pi_1"));
        UUID reserva = UUID.randomUUID();

        cobrar(reserva, reserva + ":cobrar", SERVICE).andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/pagos/")))
                .andExpect(jsonPath("$.estado", is("APROBADO")))
                .andExpect(jsonPath("$.transaccionId", is("pi_1")))
                .andExpect(jsonPath("$.montoCents", is(157500000)))
                .andExpect(jsonPath("$.motivoRechazo").doesNotExist())
                .andExpect(jsonPath("$.tokenPago").doesNotExist());
        assertEquals("PagoAprobado", eventOf(reserva));
        verify(pasarela).cobrar(157500000L, "COP", TOKEN, reserva + ":cobrar");
    }

    @Test
    void pay001Scenario2ADeclinedChargeIs201RechazadoWithPagoRechazado() throws Exception {
        gatewayAnswers(new PasarelaPagoPort.Rechazado("FONDOS_INSUFICIENTES"));
        UUID reserva = UUID.randomUUID();

        cobrar(reserva, reserva + ":cobrar", SERVICE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado", is("RECHAZADO")))
                .andExpect(jsonPath("$.motivoRechazo", is("FONDOS_INSUFICIENTES")))
                .andExpect(jsonPath("$.transaccionId").doesNotExist());
        assertEquals("PagoRechazado", eventOf(reserva));
    }

    @Test
    void pay001Scenario3AnOpenCircuitIs201WithPasarelaNoDisponible() throws Exception {
        gatewayAnswers(new PasarelaPagoPort.NoDisponible());
        UUID reserva = UUID.randomUUID();

        cobrar(reserva, reserva + ":cobrar", SERVICE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado", is("RECHAZADO")))
                .andExpect(jsonPath("$.motivoRechazo", is("PASARELA_NO_DISPONIBLE")));
    }

    @Test
    void anUnknownOutcomeIs500AndRecordsNothingSoTheWorkflowRetriesWithTheSameKey() throws Exception {
        gatewayAnswers(new PasarelaPagoPort.Indeterminado());
        UUID reserva = UUID.randomUUID();

        cobrar(reserva, reserva + ":cobrar", SERVICE).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error", is("INTERNAL_ERROR")));
        assertTrue(pagos.findByReserva(reserva).isEmpty());

        gatewayAnswers(new PasarelaPagoPort.Aprobado("pi_after_retry"));
        cobrar(reserva, reserva + ":cobrar", SERVICE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.transaccionId", is("pi_after_retry")));
    }

    @Test
    void pay002Scenario1ARepeatedKeyIs200WithTheSamePagoAndTheGatewayIsNotCalledAgain() throws Exception {
        gatewayAnswers(new PasarelaPagoPort.Aprobado("pi_2"));
        UUID reserva = UUID.randomUUID();
        cobrar(reserva, reserva + ":cobrar", SERVICE).andExpect(status().isCreated());

        cobrar(reserva, reserva + ":cobrar", SERVICE).andExpect(status().isOk())
                .andExpect(jsonPath("$.transaccionId", is("pi_2")));
        verify(pasarela, times(1)).cobrar(anyLong(), anyString(), anyString(), eq(reserva + ":cobrar"));
    }

    @Test
    void pay002Scenario2ASecondChargeForTheSameReservaWithAnotherKeyIs422() throws Exception {
        gatewayAnswers(new PasarelaPagoPort.Aprobado("pi_3"));
        UUID reserva = UUID.randomUUID();
        cobrar(reserva, reserva + ":cobrar", SERVICE).andExpect(status().isCreated());

        cobrar(reserva, UUID.randomUUID() + ":cobrar", SERVICE).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("BUSINESS_RULE_VIOLATION")));
        verify(pasarela, times(1)).cobrar(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void onlyAServiceChargesAndEveryInvalidFieldIsNamed() throws Exception {
        cobrar(UUID.randomUUID(), "saga-x:cobrar", TestKeys.bearer("\"sub\":\"" + UUID.randomUUID() + "\",\"roles\":[\"ADMIN\"]"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pagos").header("Authorization", SERVICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservaId\":\"x\",\"montoCents\":1575.5,\"moneda\":\"USD\",\"metodoPago\":\"EFECTIVO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field", containsInAnyOrder("reservaId", "usuarioId", "montoCents",
                        "moneda", "metodoPago", "tokenPago", "Idempotency-Key")));
        verify(pasarela, never()).cobrar(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void theCardTokenIsNeverStoredNorWrittenToTheLog(CapturedOutput output) throws Exception {
        gatewayAnswers(new PasarelaPagoPort.Aprobado("pi_4"));
        UUID reserva = UUID.randomUUID();
        cobrar(reserva, reserva + ":cobrar", SERVICE).andExpect(status().isCreated());
        gatewayAnswers(new PasarelaPagoPort.Indeterminado());
        cobrar(UUID.randomUUID(), UUID.randomUUID() + ":cobrar", SERVICE).andExpect(status().isInternalServerError());

        assertTrue(output.getOut().contains("unhandled error"), "the 500 was logged, with its stack trace");
        assertFalse(output.getOut().contains(TOKEN), "the card token reached the log");
        assertFalse(output.getErr().contains(TOKEN), "the card token reached the log");
        assertFalse(pagos.findByReserva(reserva).orElseThrow().toString().contains(TOKEN));
    }
}
