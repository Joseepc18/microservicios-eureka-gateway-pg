package ec.edu.upse.distribuidas.serviciopedidos.repositorio;

import ec.edu.upse.distribuidas.serviciopedidos.dominio.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {
}
