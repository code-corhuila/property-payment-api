package co.edu.corhuila.property.payment.app;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Records Pagos through E-20 for the tests of the operations that follow it. */
final class PagoFixture {

    static final String SERVICE = TestKeys.bearer("\"sub\":\"svc-workflow\",\"scope\":\"service\"");

    private PagoFixture() { }

    static String person(UUID id, String role) {
        return TestKeys.bearer("\"sub\":\"" + id + "\",\"roles\":[\"" + role + "\"]");
    }

    /** A Pago of that guest with the outcome the simulated gateway gives; returns its id. */
    static String pago(MockMvc mvc, PasarelaPagoPort pasarela, UUID guest, PasarelaPagoPort.Cobro outcome) throws Exception {
        when(pasarela.cobrar(anyLong(), anyString(), anyString(), anyString())).thenReturn(outcome);
        UUID reserva = UUID.randomUUID();
        String body = "{\"reservaId\":\"" + reserva + "\",\"usuarioId\":\"" + guest + "\",\"montoCents\":157500000,"
                + "\"moneda\":\"COP\",\"metodoPago\":\"TARJETA_CREDITO\",\"tokenPago\":\"tok_visa\"}";
        String json = mvc.perform(post("/api/v1/pagos").header("Authorization", SERVICE)
                        .header("Idempotency-Key", reserva + ":cobrar").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }
}
