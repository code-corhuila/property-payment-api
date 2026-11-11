package co.edu.corhuila.property.payment.app;

import static co.edu.corhuila.property.payment.app.PagoFixture.SERVICE;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
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

import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HU-PAY-004 over HTTP (E-21), with the gateway simulated behind its port. */
@SpringBootTest
@AutoConfigureMockMvc
class ReembolsarHttpTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    PasarelaPagoPort pasarela;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    ResultActions reembolsar(String pagoId, String key, String bearer) throws Exception {
        return mvc.perform(post("/api/v1/pagos/" + pagoId + "/reembolsos").header("Authorization", bearer)
                .header("Idempotency-Key", key));
    }

    String aprobado() throws Exception {
        return PagoFixture.pago(mvc, pasarela, UUID.randomUUID(), new PasarelaPagoPort.Aprobado("pi_77"));
    }

    @Test
    void scenario1AnApprovedPagoIsRefundedInFullThroughTheGatewayWithItsKey() throws Exception {
        String id = aprobado();
        when(pasarela.reembolsar(anyString(), anyLong(), anyString())).thenReturn(new PasarelaPagoPort.Reembolsado("re_1"));

        reembolsar(id, "saga-1:cobrar:compensar", SERVICE).andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/pagos/" + id + "/reembolsos/")))
                .andExpect(jsonPath("$.pagoId", is(id)))
                .andExpect(jsonPath("$.montoCents", is(157500000)))
                .andExpect(jsonPath("$.transaccionId", is("re_1")));
        verify(pasarela).reembolsar("pi_77", 157500000L, "saga-1:cobrar:compensar");
    }

    @Test
    void scenario2AnotherRefundWithTheSameKeyOrAnotherIs200WithTheSameReembolso() throws Exception {
        String id = aprobado();
        when(pasarela.reembolsar(anyString(), anyLong(), anyString())).thenReturn(new PasarelaPagoPort.Reembolsado("re_2"));
        String first = com.jayway.jsonpath.JsonPath.read(reembolsar(id, "saga-2:cobrar:compensar", SERVICE)
                .andReturn().getResponse().getContentAsString(), "$.id");

        reembolsar(id, "saga-2:cobrar:compensar", SERVICE).andExpect(status().isOk()).andExpect(jsonPath("$.id", is(first)));
        reembolsar(id, "another-key-0001", SERVICE).andExpect(status().isOk()).andExpect(jsonPath("$.id", is(first)));
        verify(pasarela, times(1)).reembolsar(anyString(), anyLong(), anyString());
    }

    @Test
    void scenario3ARejectedPagoIs422AndNothingReachesTheGateway() throws Exception {
        String id = PagoFixture.pago(mvc, pasarela, UUID.randomUUID(), new PasarelaPagoPort.Rechazado("TARJETA_RECHAZADA"));

        reembolsar(id, "saga-3:cobrar:compensar", SERVICE).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("BUSINESS_RULE_VIOLATION")))
                .andExpect(jsonPath("$.message", containsString("INV-006")));
        verify(pasarela, never()).reembolsar(anyString(), anyLong(), eq("saga-3:cobrar:compensar"));
    }

    @Test
    void whenTheGatewayCannotRefundNothingIsRecordedAndItIs500() throws Exception {
        String id = aprobado();
        when(pasarela.reembolsar(anyString(), anyLong(), anyString())).thenReturn(new PasarelaPagoPort.Fallido());

        reembolsar(id, "saga-4:cobrar:compensar", SERVICE).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error", is("INTERNAL_ERROR")));
        when(pasarela.reembolsar(anyString(), anyLong(), anyString())).thenReturn(new PasarelaPagoPort.Reembolsado("re_4"));
        reembolsar(id, "saga-4:cobrar:compensar", SERVICE).andExpect(status().isCreated());
    }

    @Test
    void onlyAServiceRefundsAndTheIdAndKeyAreChecked() throws Exception {
        String id = aprobado();

        reembolsar(id, "saga-5:cobrar:compensar", PagoFixture.person(UUID.randomUUID(), "ADMIN")).andExpect(status().isForbidden());
        reembolsar(UUID.randomUUID().toString(), "saga-5:cobrar:compensar", SERVICE).andExpect(status().isNotFound());
        reembolsar("not-a-uuid", "saga-5:cobrar:compensar", SERVICE).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/pagos/" + id + "/reembolsos").header("Authorization", SERVICE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field", is("Idempotency-Key")));
    }
}
