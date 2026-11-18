package ec.edu.upse.distribuidas.serviciopedidos.excepcion;

/** servicio-productos no responde, no tiene instancias registradas o superó el timeout (responde 503). */
public class ServicioProductosNoDisponibleException extends RuntimeException {

    public ServicioProductosNoDisponibleException(String detalle, Throwable causa) {
        super("El servicio de productos no está disponible: " + detalle, causa);
    }
}
