package ec.edu.upse.distribuidas.serviciopedidos.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ec.edu.upse.distribuidas.serviciopedidos.dominio.Pedido;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.PedidoNoCancelableException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.PedidoNoEncontradoException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ProductoNoEncontradoException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ServicioProductosNoDisponibleException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.StockInsuficienteException;
import ec.edu.upse.distribuidas.serviciopedidos.servicio.PedidoService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PedidoController.class)
@Import({ManejadorErrores.class, TrazaFilter.class})
class PedidoControllerTests {

    private static final String JSON_VALIDO = "{\"productoId\":1,\"cantidad\":2}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PedidoService servicio;

    private static Pedido pedido() {
        return new Pedido(1L, 2, new BigDecimal("25.00"));
    }

    @Test
    void crearDevuelve201ConLocationYHeaders() throws Exception {
        when(servicio.crear(1L, 2)).thenReturn(pedido());

        mvc.perform(post("/pedidos").contentType(MediaType.APPLICATION_JSON).content(JSON_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Instance-Id", "test-1"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.total").value(25.00))
                .andExpect(jsonPath("$.estado").value("CREADO"));
    }

    @Test
    void conservaElRequestIdRecibido() throws Exception {
        when(servicio.crear(1L, 2)).thenReturn(pedido());

        mvc.perform(post("/pedidos").header("X-Request-Id", "req-42")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON_VALIDO))
                .andExpect(header().string("X-Request-Id", "req-42"));
    }

    @Test
    void reemplazaUnRequestIdInvalido() throws Exception {
        when(servicio.listar()).thenReturn(List.of());

        mvc.perform(get("/pedidos").header("X-Request-Id", "inválido con espacios"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not("inválido con espacios")));
    }

    @Test
    void productoInexistenteDevuelve404() throws Exception {
        when(servicio.crear(1L, 2)).thenThrow(new ProductoNoEncontradoException(1L));

        mvc.perform(post("/pedidos").contentType(MediaType.APPLICATION_JSON).content(JSON_VALIDO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No existe el producto con id 1"));
    }

    @Test
    void stockInsuficienteDevuelve409() throws Exception {
        when(servicio.crear(1L, 2)).thenThrow(new StockInsuficienteException(1L, 2));

        mvc.perform(post("/pedidos").contentType(MediaType.APPLICATION_JSON).content(JSON_VALIDO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void servicioDeProductosNoDisponibleDevuelve503ConMensajeClaro() throws Exception {
        when(servicio.crear(1L, 2)).thenThrow(new ServicioProductosNoDisponibleException("timeout", null));

        mvc.perform(post("/pedidos").contentType(MediaType.APPLICATION_JSON).content(JSON_VALIDO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("El servicio de productos no está disponible: timeout"));
    }

    @Test
    void datosInvalidosDevuelven400() throws Exception {
        mvc.perform(post("/pedidos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productoId\":-1,\"cantidad\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void jsonMalformadoDevuelve400() throws Exception {
        mvc.perform(post("/pedidos").contentType(MediaType.APPLICATION_JSON).content("{roto"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tipoDeContenidoNoSoportadoDevuelve415() throws Exception {
        mvc.perform(post("/pedidos").contentType(MediaType.TEXT_PLAIN).content("hola"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void obtenerInexistenteDevuelve404() throws Exception {
        when(servicio.obtener(9L)).thenThrow(new PedidoNoEncontradoException(9L));

        mvc.perform(get("/pedidos/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.path").value("/pedidos/9"));
    }

    @Test
    void cancelarPedidoYaCanceladoDevuelve409() throws Exception {
        when(servicio.cancelar(1L)).thenThrow(
                new PedidoNoCancelableException(1L, ec.edu.upse.distribuidas.serviciopedidos.dominio.EstadoPedido.CANCELADO));

        mvc.perform(patch("/pedidos/1/cancelar")).andExpect(status().isConflict());
    }

    @Test
    void cancelarDevuelveElPedidoCancelado() throws Exception {
        Pedido cancelado = pedido();
        cancelado.cancelar();
        when(servicio.cancelar(1L)).thenReturn(cancelado);

        mvc.perform(patch("/pedidos/1/cancelar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CANCELADO"));
    }
}
