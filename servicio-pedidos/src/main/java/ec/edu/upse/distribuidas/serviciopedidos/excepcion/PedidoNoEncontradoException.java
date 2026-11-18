package ec.edu.upse.distribuidas.serviciopedidos.excepcion;

public class PedidoNoEncontradoException extends RuntimeException {

    public PedidoNoEncontradoException(Long id) {
        super("No existe el pedido con id " + id);
    }
}
