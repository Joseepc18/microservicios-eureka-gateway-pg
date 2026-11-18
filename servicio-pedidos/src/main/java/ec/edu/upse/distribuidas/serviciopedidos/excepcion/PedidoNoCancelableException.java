package ec.edu.upse.distribuidas.serviciopedidos.excepcion;

import ec.edu.upse.distribuidas.serviciopedidos.dominio.EstadoPedido;

/** Solo se pueden cancelar pedidos en estado CREADO (responde 409). */
public class PedidoNoCancelableException extends RuntimeException {

    public PedidoNoCancelableException(Long id, EstadoPedido estado) {
        super("El pedido " + id + " no se puede cancelar porque está " + estado);
    }
}
