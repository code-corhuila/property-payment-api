package co.edu.corhuila.property.payment.app;

import static co.edu.corhuila.property.payment.app.PagoFixture.SERVICE;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort;
import com.jayway.jsonpath.JsonPath;
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

/** HU-PAY-003 scenario 3 over HTTP (E-07, E-08, E-22). */
@SpringBootTest
@AutoConfigureMockMvc
class ConsultarPagoHttpTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    PasarelaPagoPort pasarela;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    ResultActions read(String path, String bearer) throws Exception {
        return mvc.perform(get("/api/v1/pagos" + path).header("Authorization", bearer));
    }

    @Test
    void theGuestWhoPaidAnAdminAndAServiceReadThePagoByIdAndByReserva() throws Exception {
        UUID guest = UUID.randomUUID();
        String id = PagoFixture.pago(mvc, pasarela, guest, new PasarelaPagoPort.Aprobado("pi_5"));
        String reserva = JsonPath.read(read("/" + id, SERVICE).andReturn().getResponse().getContentAsString(), "$.reservaId");

        read("/" + id, PagoFixture.person(guest, "GUEST")).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado", is("APROBADO")))
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
        read("/por-reserva/" + reserva, PagoFixture.person(guest, "HOST")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id)));
        read("/por-reserva/" + reserva, PagoFixture.person(UUID.randomUUID(), "ADMIN")).andExpect(status().isOk());
        read("/por-reserva/" + reserva, SERVICE).andExpect(status().isOk());
    }

    @Test
    void anotherGuestGets403AndAReservaWithoutPagoIs404() throws Exception {
        String id = PagoFixture.pago(mvc, pasarela, UUID.randomUUID(), new PasarelaPagoPort.Aprobado("pi_6"));

        read("/" + id, PagoFixture.person(UUID.randomUUID(), "GUEST")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("FORBIDDEN")));
        read("/por-reserva/" + UUID.randomUUID(), SERVICE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("NOT_FOUND")));
        read("/" + UUID.randomUUID(), SERVICE).andExpect(status().isNotFound());
        read("/not-a-uuid", SERVICE).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pagos/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void aReembolsoIsReadWithThePermissionOfItsPago() throws Exception {
        UUID guest = UUID.randomUUID();
        String id = PagoFixture.pago(mvc, pasarela, guest, new PasarelaPagoPort.Aprobado("pi_7"));
        when(pasarela.reembolsar(anyString(), anyLong(), anyString())).thenReturn(new PasarelaPagoPort.Reembolsado("re_7"));
        String reembolso = JsonPath.read(mvc.perform(post("/api/v1/pagos/" + id + "/reembolsos").header("Authorization", SERVICE)
                .header("Idempotency-Key", "saga-7:cobrar:compensar")).andReturn().getResponse().getContentAsString(), "$.id");

        read("/" + id + "/reembolsos/" + reembolso, PagoFixture.person(guest, "GUEST")).andExpect(status().isOk())
                .andExpect(jsonPath("$.transaccionId", is("re_7")))
                .andExpect(jsonPath("$.montoCents", is(157500000)));
        read("/" + id + "/reembolsos/" + reembolso, PagoFixture.person(UUID.randomUUID(), "GUEST")).andExpect(status().isForbidden());
        String other = PagoFixture.pago(mvc, pasarela, guest, new PasarelaPagoPort.Aprobado("pi_8"));
        read("/" + other + "/reembolsos/" + reembolso, SERVICE).andExpect(status().isNotFound());
    }
}
