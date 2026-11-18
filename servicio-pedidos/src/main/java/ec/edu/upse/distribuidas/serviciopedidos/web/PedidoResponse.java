package ec.edu.upse.distribuidas.serviciopedidos.web;

import ec.edu.upse.distribuidas.serviciopedidos.dominio.EstadoPedido;
import ec.edu.upse.distribuidas.serviciopedidos.dominio.Pedido;
import java.math.BigDecimal;
import java.time.Instant;

public record PedidoResponse(Long id, Long productoId, Integer cantidad, BigDecimal total,
                             EstadoPedido estado, Instant fecha) {

    public static PedidoResponse de(Pedido p) {
        return new PedidoResponse(p.getId(), p.getProductoId(), p.getCantidad(), p.getTotal(),
                p.getEstado(), p.getFecha());
    }
}
