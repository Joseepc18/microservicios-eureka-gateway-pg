package ec.edu.upse.distribuidas.servicioproductos.repositorio;

import ec.edu.upse.distribuidas.servicioproductos.dominio.Producto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductoRepository extends JpaRepository<Producto, Long> {

    /**
     * Descuenta stock en una sola sentencia atómica: la condición {@code stock >= cantidad}
     * evita el stock negativo aunque dos instancias descuenten a la vez.
     *
     * @return filas afectadas (0 si el producto no existe o no hay stock suficiente)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Producto p SET p.stock = p.stock - :cantidad WHERE p.id = :id AND p.stock >= :cantidad")
    int descontarStock(@Param("id") Long id, @Param("cantidad") int cantidad);

    /**
     * Repone stock en una sola sentencia atómica (por ejemplo, al cancelar un pedido): suma sobre
     * el valor actual, así que no pisa los descuentos que hagan otras instancias a la vez.
     *
     * @return filas afectadas (0 si el producto no existe)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Producto p SET p.stock = p.stock + :cantidad WHERE p.id = :id")
    int reponerStock(@Param("id") Long id, @Param("cantidad") int cantidad);
}
