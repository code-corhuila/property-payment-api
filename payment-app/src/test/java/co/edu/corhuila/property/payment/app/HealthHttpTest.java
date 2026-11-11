package co.edu.corhuila.property.payment.app;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort;
import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort.Disponibilidad;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** /health reports the circuit around Stripe as paymentGateway, inside a 200 (payment-service.yaml). */
@SpringBootTest
@AutoConfigureMockMvc
class HealthHttpTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    PasarelaPagoPort pasarela;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @ParameterizedTest
    @CsvSource({"OK, ok, ok", "DEGRADADA, degraded, degraded", "CAIDA, down, degraded"})
    void theStateOfTheCircuitIsPaymentGateway(Disponibilidad disponibilidad, String gateway, String status) throws Exception {
        when(pasarela.disponibilidad()).thenReturn(disponibilidad);
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is(status)))
                .andExpect(jsonPath("$.dependencies.paymentGateway", is(gateway)))
                .andExpect(jsonPath("$.timestamp").isString());
    }
}
