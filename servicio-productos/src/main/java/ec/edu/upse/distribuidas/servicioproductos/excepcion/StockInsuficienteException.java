package ec.edu.upse.distribuidas.servicioproductos.excepcion;

public class StockInsuficienteException extends RuntimeException {

    public StockInsuficienteException(Long id, int solicitado) {
        super("Stock insuficiente para el producto " + id + " (solicitado: " + solicitado + ")");
    }
}
