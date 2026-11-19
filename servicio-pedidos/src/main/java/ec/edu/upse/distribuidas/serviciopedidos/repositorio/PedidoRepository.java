package ec.edu.upse.distribuidas.serviciopedidos.repositorio;

import ec.edu.upse.distribuidas.serviciopedidos.dominio.EstadoPedido;
import ec.edu.upse.distribuidas.serviciopedidos.dominio.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    /**
     * Cambia el estado solo si el pedido sigue en {@code actual}, en una sola sentencia atómica (en
     * el primario, porque es una escritura). Si dos solicitudes cancelan el mismo pedido a la vez,
     * solo una cambia la fila: la otra recibe 0 y no repone stock por segunda vez.
     *
     * @return filas afectadas (0 si el pedido no existe o ya no estaba en {@code actual})
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Pedido p SET p.estado = :nuevo WHERE p.id = :id AND p.estado = :actual")
    int cambiarEstado(@Param("id") Long id, @Param("actual") EstadoPedido actual,
                      @Param("nuevo") EstadoPedido nuevo);
}
