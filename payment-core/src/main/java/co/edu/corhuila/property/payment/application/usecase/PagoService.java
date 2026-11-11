package co.edu.corhuila.property.payment.application.usecase;

import co.edu.corhuila.property.payment.application.port.in.AccessDeniedException;
import co.edu.corhuila.property.payment.application.port.in.Caller;
import co.edu.corhuila.property.payment.application.port.in.CobroIndeterminadoException;
import co.edu.corhuila.property.payment.application.port.in.PagoNoEncontradoException;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases.Cobrar;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases.CobrarCommand;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases.ConsultarPagos;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases.Cobro;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases.Reembolsar;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases.Reversion;
import co.edu.corhuila.property.payment.application.port.out.PagoDuplicadoException;
import co.edu.corhuila.property.payment.application.port.out.PagoRepository;
import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort;
import co.edu.corhuila.property.payment.domain.model.DomainException;
import co.edu.corhuila.property.payment.domain.model.Money;
import co.edu.corhuila.property.payment.domain.model.Pago;
import co.edu.corhuila.property.payment.domain.model.Reembolso;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The use cases of the ledger. It orchestrates; the rules live in Pago and Reembolso. */
public class PagoService implements Cobrar, Reembolsar, ConsultarPagos {

    private final PagoRepository pagos;
    private final PasarelaPagoPort pasarela;

    public PagoService(PagoRepository pagos, PasarelaPagoPort pasarela) {
        this.pagos = pagos;
        this.pasarela = pasarela;
    }

    /**
     * HU-PAY-001 and HU-PAY-002, with the table of E-20. Service credential only. A key
     * already used answers its Pago and the gateway is not called again (INV-004); a
     * Reserva that already has a Pago with another key is 422. The gateway gets the same
     * key, so two requests that race past the ledger still charge once.
     */
    @Override
    public Cobro cobrar(CobrarCommand c) {
        c.caller().requireService();
        Optional<Pago> first = pagos.findByKey(c.idempotencyKey());
        if (first.isPresent()) {
            return new Cobro(first.get(), false);
        }
        if (pagos.findByReserva(c.reservaId()).isPresent()) {
            throw new PagoDuplicadoException();
        }
        Money monto = new Money(c.montoCents(), c.moneda());
        Pago pago = switch (pasarela.cobrar(c.montoCents(), c.moneda(), c.tokenPago(), c.idempotencyKey())) {
            case PasarelaPagoPort.Aprobado a ->
                    Pago.aprobado(c.reservaId(), c.usuarioId(), monto, c.metodoPago(), a.transaccionId(), c.idempotencyKey());
            case PasarelaPagoPort.Rechazado r ->
                    Pago.rechazado(c.reservaId(), c.usuarioId(), monto, c.metodoPago(), r.motivoRechazo(), c.idempotencyKey());
            case PasarelaPagoPort.NoDisponible n ->
                    Pago.rechazado(c.reservaId(), c.usuarioId(), monto, c.metodoPago(), "PASARELA_NO_DISPONIBLE",
                            c.idempotencyKey());
            case PasarelaPagoPort.Indeterminado i -> throw new CobroIndeterminadoException();
        };
        PagoRepository.Created<Pago> stored = pagos.createPago(pago, List.of(PagoEvents.of(pago, c.correlationId())));
        return new Cobro(stored.value(), stored.created());
    }

    /**
     * HU-PAY-004 (INV-006). Service credential only. A Pago already refunded, with this
     * key or another, answers its Reembolso and the gateway is not called again; a
     * RECHAZADO one is 422, before anything reaches the gateway. The refund is a new
     * ledger row for the full amount; the Pago stays as it was. When the gateway cannot
     * refund it now, nothing is recorded and the answer is 500: the workflow retries.
     */
    @Override
    public Reversion reembolsar(Caller caller, UUID pagoId, String idempotencyKey) {
        caller.requireService();
        Pago pago = pagos.findById(pagoId).orElseThrow(() -> new PagoNoEncontradoException("the payment does not exist"));
        Optional<Reembolso> first = pagos.findReembolsoByPago(pagoId);
        if (first.isPresent()) {
            return new Reversion(first.get(), false);
        }
        if (!pago.fueAprobado()) {
            throw new DomainException("INV-006: only an APROBADO payment is refunded; this one is " + pago.getEstado());
        }
        PasarelaPagoPort.Reversion done = pasarela.reembolsar(pago.getTransaccionId(), pago.getMonto().getMontoCents(),
                idempotencyKey);
        if (!(done instanceof PasarelaPagoPort.Reembolsado reembolsado)) {
            throw new IllegalStateException("the gateway could not refund the payment now: nothing was recorded");
        }
        PagoRepository.Created<Reembolso> stored = pagos.createReembolso(
                Reembolso.crear(pago, reembolsado.transaccionId(), idempotencyKey));
        return new Reversion(stored.value(), stored.created());
    }

    /** HU-PAY-003 scenario 3. A Pago of another guest is 403; one that does not exist, 404. */
    @Override
    public Pago porId(Caller caller, UUID pagoId) {
        return readable(caller, pagos.findById(pagoId));
    }

    /** E-08: 404 means no Pago is recorded for the Reserva, not that no charge was attempted. */
    @Override
    public Pago porReserva(Caller caller, UUID reservaId) {
        return readable(caller, pagos.findByReserva(reservaId));
    }

    /** E-22: same permission as the Pago it reverses; a Reembolso of another Pago is 404. */
    @Override
    public Reembolso reembolso(Caller caller, UUID pagoId, UUID reembolsoId) {
        porId(caller, pagoId);
        return pagos.findReembolso(reembolsoId).filter(r -> r.getPagoId().value().equals(pagoId))
                .orElseThrow(() -> new PagoNoEncontradoException("the refund does not exist"));
    }

    /** pagos:read is own-only for a person; an ADMIN and a service read any (security-policy.md). */
    private static Pago readable(Caller caller, Optional<Pago> found) {
        Pago pago = found.orElseThrow(() -> new PagoNoEncontradoException("the payment does not exist"));
        if (!caller.service() && !caller.isAdmin() && !caller.personId().equals(pago.getUsuarioId())) {
            throw new AccessDeniedException("the payment belongs to another guest");
        }
        return pago;
    }
}
