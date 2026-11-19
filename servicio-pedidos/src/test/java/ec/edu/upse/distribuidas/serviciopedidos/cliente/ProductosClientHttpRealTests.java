package ec.edu.upse.distribuidas.serviciopedidos.cliente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ServicioProductosNoDisponibleException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

/**
 * Cliente armado con la misma fábrica de producción contra un servidor HTTP real y local.
 * Cubre lo que un cliente simulado no puede detectar: el método PATCH sobre HTTP real y los timeouts.
 */
class ProductosClientHttpRealTests {

    private HttpServer servidor;
    private final AtomicReference<String> metodo = new AtomicReference<>();
    private final AtomicReference<String> ruta = new AtomicReference<>();
    private final AtomicReference<String> cuerpo = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();
    private volatile long demoraMs = 0;

    @BeforeEach
    void iniciar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/productos", exchange -> {
            metodo.set(exchange.getRequestMethod());
            ruta.set(exchange.getRequestURI().getPath());
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            cuerpo.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(demoraMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] respuesta = "{\"id\":1,\"nombre\":\"Mouse\",\"precio\":12.50,\"stock\":10}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respuesta.length);
            exchange.getResponseBody().write(respuesta);
            exchange.close();
        });
        servidor.start();
    }

    @AfterEach
    void detener() {
        servidor.stop(0);
        MDC.clear();
    }

    private ProductosClient cliente(Duration lectura) {
        String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        return ClienteProductosConfig.crear(RestClient.builder(), base, Duration.ofSeconds(2), lectura);
    }

    @Test
    void elPatchLlegaAlServidorConSuCuerpoYElRequestId() {
        MDC.put("requestId", "real-1");

        cliente(Duration.ofSeconds(5)).descontarStock(1L, 3);

        assertThat(metodo.get()).isEqualTo("PATCH");
        assertThat(cuerpo.get()).contains("\"cantidad\":3");
        assertThat(requestId.get()).isEqualTo("real-1");
    }

    @Test
    void reponerStockEnviaUnPatchALaRutaDeReposicion() {
        cliente(Duration.ofSeconds(5)).reponerStock(1L, 4);

        assertThat(metodo.get()).isEqualTo("PATCH");
        assertThat(ruta.get()).isEqualTo("/productos/1/stock/reponer");
        assertThat(cuerpo.get()).contains("\"cantidad\":4");
    }

    @Test
    void elGetDevuelveElProducto() {
        assertThat(cliente(Duration.ofSeconds(5)).obtener(1L).nombre()).isEqualTo("Mouse");
        assertThat(metodo.get()).isEqualTo("GET");
    }

    @Test
    void unaRespuestaLentaSuperaElTimeoutDeLecturaYDa503() {
        demoraMs = 1500;

        assertThatThrownBy(() -> cliente(Duration.ofMillis(300)).obtener(1L))
                .isInstanceOf(ServicioProductosNoDisponibleException.class)
                .hasMessageContaining("sin respuesta");
    }

    @Test
    void unServidorCaidoDa503() {
        ProductosClient cliente = cliente(Duration.ofSeconds(1));
        servidor.stop(0);

        assertThatThrownBy(() -> cliente.obtener(1L)).isInstanceOf(ServicioProductosNoDisponibleException.class);
    }
}
