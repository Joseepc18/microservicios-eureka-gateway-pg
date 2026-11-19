package ec.edu.upse.distribuidas.serviciopedidos.cliente;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ServicioProductosNoDisponibleException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Reintento del LoadBalancer con una instancia de servicio-productos caída, sobre HTTP real.
 *
 * <p>El registro tiene dos instancias de {@code servicio-productos} (con el DiscoveryClient simple
 * en lugar de Eureka): una no acepta conexiones, como una instancia que Eureka aún no expulsó, y
 * la otra responde. Un {@code GET} que cae en la caída se repite en la otra; un {@code PATCH} no
 * se repite, para no descontar stock dos veces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProductosClientReintentoTests {

    private static final AtomicInteger GETS = new AtomicInteger();
    private static final AtomicInteger PATCHES = new AtomicInteger();
    private static final HttpServer VIVA = iniciarInstanciaViva();
    private static final int PUERTO_CAIDO = puertoSinServidor();

    @Autowired
    private ProductosClient cliente;

    @DynamicPropertySource
    static void registro(DynamicPropertyRegistry registro) {
        registro.add("spring.cloud.discovery.client.simple.instances.servicio-productos[0].uri",
                () -> "http://127.0.0.1:" + PUERTO_CAIDO);
        registro.add("spring.cloud.discovery.client.simple.instances.servicio-productos[1].uri",
                () -> "http://127.0.0.1:" + VIVA.getAddress().getPort());
    }

    @AfterAll
    static void detener() {
        VIVA.stop(0);
    }

    @Test
    void unGetQueCaeEnLaInstanciaCaidaSeReintentaEnLaOtra() {
        GETS.set(0);

        for (int i = 0; i < 6; i++) {
            assertThat(cliente.obtener(1L).nombre()).isEqualTo("Mouse");
        }

        // Las 6 consultas respondieron, aunque por turnos alrededor de la mitad cayó primero en la
        // instancia caída: todas terminaron en la viva.
        assertThat(GETS.get()).isEqualTo(6);
    }

    @Test
    void unPatchQueCaeEnLaInstanciaCaidaNoSeReintenta() {
        PATCHES.set(0);
        int correctos = 0;
        int fallidos = 0;

        for (int i = 0; i < 4; i++) {
            try {
                cliente.descontarStock(1L, 1);
                correctos++;
            } catch (ServicioProductosNoDisponibleException e) {
                fallidos++;
            }
        }

        // Por turnos, alguno cae en la instancia caída y falla (no se reintenta en la viva), y la
        // viva recibe exactamente un PATCH por cada descuento correcto: nunca uno repetido.
        assertThat(fallidos).isPositive();
        assertThat(PATCHES.get()).isEqualTo(correctos);
    }

    private static HttpServer iniciarInstanciaViva() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/productos", exchange -> {
                if ("PATCH".equals(exchange.getRequestMethod())) {
                    PATCHES.incrementAndGet();
                } else {
                    GETS.incrementAndGet();
                }
                exchange.getRequestBody().readAllBytes();
                byte[] respuesta = "{\"id\":1,\"nombre\":\"Mouse\",\"precio\":12.50,\"stock\":10}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, respuesta.length);
                exchange.getResponseBody().write(respuesta);
                exchange.close();
            });
            servidor.start();
            return servidor;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Un puerto local libre en el que nadie escucha: las conexiones se rechazan al instante. */
    private static int puertoSinServidor() {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
