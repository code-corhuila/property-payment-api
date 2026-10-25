package co.edu.corhuila.property.payment.domain.model;

import java.util.Objects;

/** A monetary value as an integer in minor units, never a decimal (07-api/guidelines.md, D-C8). */
public final class Money {

    private final long montoCents;
    private final String moneda;

    public Money(long montoCents, String moneda) {
        if (montoCents < 0) {
            throw new DomainException("El monto no puede ser negativo");
        }
        if (moneda == null || moneda.isBlank()) {
            throw new DomainException("La moneda es requerida");
        }
        this.montoCents = montoCents;
        this.moneda = moneda;
    }

    public boolean esMenorOIgualACero() {
        return montoCents <= 0;
    }

    public long getMontoCents() { return montoCents; }
    public String getMoneda() { return moneda; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        return montoCents == other.montoCents && moneda.equals(other.moneda);
    }

    @Override
    public int hashCode() {
        return Objects.hash(montoCents, moneda);
    }
}
