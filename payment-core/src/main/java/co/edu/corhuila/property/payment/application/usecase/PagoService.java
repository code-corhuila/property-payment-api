package co.edu.corhuila.property.payment.application.usecase;

import co.edu.corhuila.property.payment.application.port.in.CobroIndeterminadoException;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases.Cobrar;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases.CobrarCommand;
import co.edu.corhuila.property.payment.application.port.in.PagoUseCases.Cobro;
import co.edu.corhuila.property.payment.application.port.out.PagoDuplicadoException;
import co.edu.corhuila.property.payment.application.port.out.PagoRepository;
import co.edu.corhuila.property.payment.application.port.out.PasarelaPagoPort;
import co.edu.corhuila.property.payment.domain.model.Money;
import co.edu.corhuila.property.payment.domain.model.Pago;
import java.util.List;
import java.util.Optional;

/** The use cases of the ledger. It orchestrates; the rules live in Pago and Reembolso. */
public class PagoService implements Cobrar {

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
}
