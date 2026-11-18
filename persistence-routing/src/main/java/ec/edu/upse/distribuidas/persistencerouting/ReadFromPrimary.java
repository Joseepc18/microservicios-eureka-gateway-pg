package ec.edu.upse.distribuidas.persistencerouting;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un método (o todos los de una clase) como lectura crítica: sus consultas van al
 * primario aunque la transacción sea de solo lectura. Ver {@link PrimaryRead}.
 *
 * <pre>{@code
 * @ReadFromPrimary
 * @Transactional(readOnly = true)
 * public Pedido buscarRecienCreado(Long id) { ... }
 * }</pre>
 *
 * <p>Funciona en beans de Spring llamados desde otro bean (a través del proxy), igual que
 * {@code @Transactional}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReadFromPrimary {
}
