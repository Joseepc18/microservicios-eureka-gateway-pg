package ec.edu.upse.distribuidas.serviciopedidos.cliente;

import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ProductoNoEncontradoException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ServicioProductosNoDisponibleException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.StockInsuficienteException;
import java.util.Map;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP de servicio-productos. La base de la URL es el nombre lógico de Eureka
 * ({@code http://servicio-productos}); el LoadBalancer elige la instancia en cada llamada.
 * Traduce las respuestas de error a excepciones propias del dominio de pedidos.
 */
public class ProductosClient {

    private final RestClient rest;

    public ProductosClient(RestClient rest) {
        this.rest = rest;
    }

    /**
     * Consulta un producto. Si la instancia elegida no responde, el LoadBalancer repite la consulta
     * una vez en otra instancia ({@code spring.cloud.loadbalancer.retry} en application.yml).
     */
    public ProductoDto obtener(Long id) {
        try {
            return rest.get().uri("/productos/{id}", id).retrieve().body(ProductoDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductoNoEncontradoException(id);
        } catch (RuntimeException e) {
            throw noDisponible(e);
        }
    }

    /**
     * Descuenta stock. No se reintenta (el reintento del LoadBalancer solo cubre GET): repetir un
     * PATCH podría descontar dos veces.
     */
    public void descontarStock(Long id, int cantidad) {
        try {
            rest.patch().uri("/productos/{id}/stock", id)
                    .body(Map.of("cantidad", cantidad))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductoNoEncontradoException(id);
        } catch (HttpClientErrorException.Conflict e) {
            throw new StockInsuficienteException(id, cantidad);
        } catch (RuntimeException e) {
            throw noDisponible(e);
        }
    }

    /**
     * Repone stock al cancelar un pedido. No se reintenta: repetir el PATCH podría reponer dos
     * veces.
     */
    public void reponerStock(Long id, int cantidad) {
        try {
            rest.patch().uri("/productos/{id}/stock/reponer", id)
                    .body(Map.of("cantidad", cantidad))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductoNoEncontradoException(id);
        } catch (RuntimeException e) {
            throw noDisponible(e);
        }
    }

    private static ServicioProductosNoDisponibleException noDisponible(RuntimeException causa) {
        String detalle;
        if (causa instanceof ResourceAccessException) {
            detalle = "sin respuesta (timeout o error de conexión)";
        } else if (sinInstancias(causa)) {
            detalle = "no hay instancias registradas en Eureka";
        } else if (causa instanceof HttpStatusCodeException http) {
            detalle = "respondió con estado " + http.getStatusCode().value();
        } else {
            detalle = causa.getClass().getSimpleName();
        }
        return new ServicioProductosNoDisponibleException(detalle, causa);
    }

    /**
     * El LoadBalancer no encontró instancias. Sin reintento lanza {@code IllegalStateException("No
     * instances available ...")}; con reintento (spring-retry), {@code IllegalArgumentException("Service
     * Instance cannot be null ...")}.
     */
    private static boolean sinInstancias(RuntimeException causa) {
        String mensaje = causa.getMessage();
        return mensaje != null
                && (causa instanceof IllegalStateException && mensaje.startsWith("No instances available")
                    || causa instanceof IllegalArgumentException && mensaje.startsWith("Service Instance cannot be null"));
    }
}
