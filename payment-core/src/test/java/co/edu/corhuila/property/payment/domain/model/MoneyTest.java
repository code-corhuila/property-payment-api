package co.edu.corhuila.property.payment.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void rejectsANegativeAmount() {
        assertThrows(DomainException.class, () -> new Money(-1, "COP"));
    }

    @Test
    void requiresACurrency() {
        assertThrows(DomainException.class, () -> new Money(100, null));
        assertThrows(DomainException.class, () -> new Money(100, " "));
    }

    @Test
    void knowsWhenItIsZero() {
        assertTrue(new Money(0, "COP").esMenorOIgualACero());
        assertFalse(new Money(1, "COP").esMenorOIgualACero());
    }

    @Test
    void isAValueObject() {
        assertEquals(new Money(157500000L, "COP"), new Money(157500000L, "COP"));
        assertNotEquals(new Money(157500000L, "COP"), new Money(157500000L, "USD"));
    }
}
