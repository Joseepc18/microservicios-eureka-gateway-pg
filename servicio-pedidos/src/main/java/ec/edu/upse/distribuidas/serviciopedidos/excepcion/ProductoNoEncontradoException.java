package ec.edu.upse.distribuidas.serviciopedidos.excepcion;

/** El producto que se quiere pedir no existe en servicio-productos (responde 404). */
public class ProductoNoEncontradoException extends RuntimeException {

    public ProductoNoEncontradoException(Long productoId) {
        super("No existe el producto con id " + productoId);
    }
}
