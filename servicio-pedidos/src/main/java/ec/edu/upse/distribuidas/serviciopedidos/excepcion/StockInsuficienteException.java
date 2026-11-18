package ec.edu.upse.distribuidas.serviciopedidos.excepcion;

/** No hay stock suficiente para atender el pedido (responde 409). */
public class StockInsuficienteException extends RuntimeException {

    public StockInsuficienteException(Long productoId, int solicitado) {
        super("Stock insuficiente para el producto " + productoId + " (solicitado: " + solicitado + ")");
    }
}
