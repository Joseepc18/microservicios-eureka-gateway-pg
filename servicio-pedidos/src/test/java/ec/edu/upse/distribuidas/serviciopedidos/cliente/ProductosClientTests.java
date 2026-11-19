package ec.edu.upse.distribuidas.serviciopedidos.cliente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ProductoNoEncontradoException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ServicioProductosNoDisponibleException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.StockInsuficienteException;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Cliente HTTP simulado: no se llama a un servicio-productos real. */
class ProductosClientTests {

    private static final String BASE = "http://servicio-productos";

    private MockRestServiceServer servidor;
    private ProductosClient cliente;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE)
                .requestInterceptor(new RequestIdInterceptor());
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new ProductosClient(builder.build());
    }

    @AfterEach
    void limpiar() {
        MDC.clear();
    }

    @Test
    void obtenerDevuelveElProducto() {
        servidor.expect(requestTo(BASE + "/productos/1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\":1,\"nombre\":\"Mouse\",\"descripcion\":\"x\",\"precio\":12.50,\"stock\":150}",
                        MediaType.APPLICATION_JSON));

        ProductoDto producto = cliente.obtener(1L);

        assertThat(producto.nombre()).isEqualTo("Mouse");
        assertThat(producto.precio()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(producto.stock()).isEqualTo(150);
        servidor.verify();
    }

    @Test
    void obtenerInexistenteLanzaProductoNoEncontrado() {
        servidor.expect(requestTo(BASE + "/productos/9")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> cliente.obtener(9L)).isInstanceOf(ProductoNoEncontradoException.class);
    }

    @Test
    void errorDelServidorSeTraduceANoDisponible() {
        servidor.expect(requestTo(BASE + "/productos/1")).andRespond(withServerError());

        assertThatThrownBy(() -> cliente.obtener(1L))
                .isInstanceOf(ServicioProductosNoDisponibleException.class);
    }

    @Test
    void errorDeConexionSeTraduceANoDisponible() {
        servidor.expect(requestTo(BASE + "/productos/1")).andRespond(withException(new IOException("timeout")));

        assertThatThrownBy(() -> cliente.obtener(1L))
                .isInstanceOf(ServicioProductosNoDisponibleException.class);
    }

    @Test
    void descontarStockEnviaElPatchConLaCantidad() {
        servidor.expect(requestTo(BASE + "/productos/1/stock"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("{\"cantidad\":3}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        cliente.descontarStock(1L, 3);

        servidor.verify();
    }

    @Test
    void descontarStockConConflictoLanzaStockInsuficiente() {
        servidor.expect(requestTo(BASE + "/productos/1/stock")).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> cliente.descontarStock(1L, 500)).isInstanceOf(StockInsuficienteException.class);
    }

    @Test
    void descontarStockDeProductoInexistenteLanzaNoEncontrado() {
        servidor.expect(requestTo(BASE + "/productos/9/stock")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> cliente.descontarStock(9L, 1)).isInstanceOf(ProductoNoEncontradoException.class);
    }

    @Test
    void descontarStockNoSeReintentaSiElServicioFalla() {
        // expect() una sola vez: un segundo intento haría fallar la prueba con "no further requests expected".
        servidor.expect(requestTo(BASE + "/productos/1/stock")).andRespond(withServerError());

        assertThatThrownBy(() -> cliente.descontarStock(1L, 1))
                .isInstanceOf(ServicioProductosNoDisponibleException.class);
        servidor.verify();
    }

    @Test
    void propagaElRequestIdDeLaSolicitudActual() {
        MDC.put("requestId", "abc-123");
        servidor.expect(requestTo(BASE + "/productos/1"))
                .andExpect(header("X-Request-Id", "abc-123"))
                .andRespond(withSuccess("{\"id\":1,\"precio\":1,\"stock\":1}", MediaType.APPLICATION_JSON));

        cliente.obtener(1L);

        servidor.verify();
    }

    @Test
    void sinRequestIdNoAgregaElHeader() {
        servidor.expect(requestTo(BASE + "/productos/1"))
                .andExpect(request -> assertThat(request.getHeaders().containsKey("X-Request-Id")).isFalse())
                .andRespond(withSuccess("{\"id\":1,\"precio\":1,\"stock\":1}", MediaType.APPLICATION_JSON));

        cliente.obtener(1L);

        servidor.verify();
    }

    @Test
    void sinInstanciasEnEurekaSeExplicaEnElMensaje() {
        // Así fallan las llamadas cuando el LoadBalancer (con reintento) no tiene instancias.
        assertThat(clienteQueFallaCon(new IllegalArgumentException(
                "Service Instance cannot be null, serviceId: servicio-productos")))
                .hasMessageContaining("no hay instancias registradas en Eureka");
        // Y así sin reintento.
        assertThat(clienteQueFallaCon(new IllegalStateException(
                "No instances available for servicio-productos")))
                .hasMessageContaining("no hay instancias registradas en Eureka");
    }

    private static ServicioProductosNoDisponibleException clienteQueFallaCon(RuntimeException error) {
        ProductosClient falla = new ProductosClient(RestClient.builder().baseUrl(BASE)
                .requestInterceptor((request, body, execution) -> {
                    throw error;
                })
                .build());
        try {
            falla.obtener(1L);
            throw new AssertionError("Se esperaba ServicioProductosNoDisponibleException");
        } catch (ServicioProductosNoDisponibleException e) {
            return e;
        }
    }
}
