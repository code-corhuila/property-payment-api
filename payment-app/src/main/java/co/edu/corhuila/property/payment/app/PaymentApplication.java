package co.edu.corhuila.property.payment.app;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "co.edu.corhuila.property.payment")
public class PaymentApplication {
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--outbox-relay")) {
            // The outbox relay: the same jar, another process (ADR-005), without a web server.
            new SpringApplicationBuilder(OutboxRelayApplication.class).web(WebApplicationType.NONE).run(args);
            return;
        }
        SpringApplication.run(PaymentApplication.class, args);
    }
}
