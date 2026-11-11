package co.edu.corhuila.property.payment.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.payment.application.port.out.PagoDuplicadoException;
import co.edu.corhuila.property.payment.application.port.out.PagoRepository.Created;
import co.edu.corhuila.property.payment.application.usecase.PagoEvents;
import co.edu.corhuila.property.payment.domain.model.EstadoPago;
import co.edu.corhuila.property.payment.domain.model.MetodoPago;
import co.edu.corhuila.property.payment.domain.model.Money;
import co.edu.corhuila.property.payment.domain.model.Pago;
import co.edu.corhuila.property.payment.domain.model.Reembolso;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger against the REAL engine with the schema of property-payment-db applied, as
 * payment_app. Skipped without TEST_DATABASE_URL (Annex C); scripts/test-db.sh prepares one.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcPagoRepositoryIntegrationTest {

    JdbcPagoRepository repository;
    JdbcTemplate jdbc;

    @BeforeEach
    void connect() {
        var dataSource = new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),
                System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcPagoRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    static String key() {
        return UUID.randomUUID() + ":cobrar";
    }

    static Pago aprobado(UUID reserva, String key) {
        return Pago.aprobado(reserva, UUID.randomUUID(), new Money(157500000, "COP"), MetodoPago.TARJETA_CREDITO,
                "pi_" + UUID.randomUUID(), key);
    }

    Created<Pago> create(Pago p) {
        return repository.createPago(p, List.of(PagoEvents.of(p, "it-trace")));
    }

    @Test
    void roundTripKeepsEveryFieldAndWritesItsEventInTheSameTransaction() {
        Pago p = Pago.rechazado(UUID.randomUUID(), UUID.randomUUID(), new Money(45000, "COP"), MetodoPago.TARJETA_DEBITO,
                "PASARELA_NO_DISPONIBLE", key());
        assertTrue(create(p).created());

        Pago stored = repository.findById(p.getId().value()).orElseThrow();
        assertEquals(EstadoPago.RECHAZADO, stored.getEstado());
        assertEquals("PASARELA_NO_DISPONIBLE", stored.getMotivoRechazo());
        assertEquals(p.getIdempotencyKey(), stored.getIdempotencyKey());
        assertEquals(p.getCreatedAt(), stored.getCreatedAt());
        assertEquals(p.getId(), repository.findByReserva(p.getReservaId()).orElseThrow().getId());
        assertEquals("PagoRechazado", jdbc.queryForObject("SELECT event_type FROM payment.outbox_event WHERE aggregate_id = ?",
                String.class, p.getId().value()));
    }

    @Test
    void aRepeatedKeyReturnsTheFirstPagoAndAnotherKeyForTheSameReservaIsRefused() {
        UUID reserva = UUID.randomUUID();
        String key = key();
        Pago first = aprobado(reserva, key);
        create(first);

        Created<Pago> retry = create(aprobado(reserva, key));
        assertFalse(retry.created());
        assertEquals(first.getId(), retry.value().getId());
        assertThrows(PagoDuplicadoException.class, () -> create(aprobado(reserva, key())));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment.payment WHERE reservation_id = ?",
                Integer.class, reserva));
    }

    @Test
    void hu002Scenario3TwoRequestsWithTheSameKeyAtOnceRecordOnePago() {
        UUID reserva = UUID.randomUUID();
        String key = key();
        var a = CompletableFuture.supplyAsync(() -> create(aprobado(reserva, key)));
        var b = CompletableFuture.supplyAsync(() -> create(aprobado(reserva, key)));

        assertEquals(a.join().value().getId(), b.join().value().getId());
        assertTrue(a.join().created() ^ b.join().created());
    }

    @Test
    void aPagoIsRefundedOnceAsANewRowAndStaysAsItWas() {
        Pago p = aprobado(UUID.randomUUID(), key());
        create(p);
        Reembolso first = Reembolso.crear(p, "re_" + UUID.randomUUID(), p.getIdempotencyKey() + ":compensar");

        assertTrue(repository.createReembolso(first).created());
        Created<Reembolso> again = repository.createReembolso(Reembolso.crear(p, "re_x", UUID.randomUUID() + ":other"));
        assertFalse(again.created());
        assertEquals(first.getId(), again.value().getId());
        assertEquals(first.getId(), repository.findReembolsoByPago(p.getId().value()).orElseThrow().getId());
        assertEquals(157500000L, repository.findReembolso(first.getId().value()).orElseThrow().getMonto().getMontoCents());
        assertEquals(EstadoPago.APROBADO, repository.findById(p.getId().value()).orElseThrow().getEstado());
    }

    @Test
    void hu003TheLedgerIsAppendOnlyForTheService() {
        Pago p = aprobado(UUID.randomUUID(), key());
        create(p);

        assertThrows(BadSqlGrammarException.class,
                () -> jdbc.update("UPDATE payment.payment SET status = 'RECHAZADO' WHERE id = ?", p.getId().value()));
        assertThrows(BadSqlGrammarException.class,
                () -> jdbc.update("DELETE FROM payment.payment WHERE id = ?", p.getId().value()));
    }
}
