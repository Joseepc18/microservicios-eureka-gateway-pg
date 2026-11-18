package ec.edu.upse.distribuidas.servicioproductos.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ec.edu.upse.distribuidas.servicioproductos.dominio.Producto;
import ec.edu.upse.distribuidas.servicioproductos.excepcion.ProductoNoEncontradoException;
import ec.edu.upse.distribuidas.servicioproductos.excepcion.StockInsuficienteException;
import ec.edu.upse.distribuidas.servicioproductos.servicio.ProductoService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductoController.class)
@Import({ManejadorErrores.class, InstanceIdFilter.class, RequestIdFilter.class})
class ProductoControllerTests {

    private static final String JSON_VALIDO =
            "{\"nombre\":\"Cable HDMI\",\"descripcion\":\"2 metros\",\"precio\":8.50,\"stock\":30}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductoService servicio;

    private static Producto producto() {
        return new Producto("Cable HDMI", "2 metros", new BigDecimal("8.50"), 30);
    }

    @Test
    void listarDevuelveProductosYHeaderDeInstancia() throws Exception {
        when(servicio.listar()).thenReturn(List.of(producto()));

        mvc.perform(get("/productos"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Instance-Id", "test-1"))
                .andExpect(jsonPath("$[0].nombre").value("Cable HDMI"))
                .andExpect(jsonPath("$[0].stock").value(30));
    }

    @Test
    void conservaElRequestIdRecibidoYGeneraUnoSiFalta() throws Exception {
        when(servicio.listar()).thenReturn(List.of());

        mvc.perform(get("/productos").header("X-Request-Id", "req-42"))
                .andExpect(header().string("X-Request-Id", "req-42"));
        mvc.perform(get("/productos")).andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void obtenerInexistenteDevuelve404Uniforme() throws Exception {
        when(servicio.obtener(99L)).thenThrow(new ProductoNoEncontradoException(99L));

        mvc.perform(get("/productos/99"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Instance-Id", "test-1"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("No existe el producto con id 99"))
                .andExpect(jsonPath("$.path").value("/productos/99"));
    }

    @Test
    void crearDevuelve201ConLocation() throws Exception {
        when(servicio.crear(any())).thenReturn(producto());

        mvc.perform(post("/productos").contentType(MediaType.APPLICATION_JSON).content(JSON_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("Cable HDMI"));
    }

    @Test
    void crearConDatosInvalidosDevuelve400() throws Exception {
        String invalido = "{\"nombre\":\"\",\"precio\":-1,\"stock\":-5}";

        mvc.perform(post("/productos").contentType(MediaType.APPLICATION_JSON).content(invalido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void crearConJsonMalformadoDevuelve400() throws Exception {
        mvc.perform(post("/productos").contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void idNoNumericoDevuelve400() throws Exception {
        mvc.perform(get("/productos/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void actualizarInexistenteDevuelve404() throws Exception {
        when(servicio.actualizar(eq(99L), any())).thenThrow(new ProductoNoEncontradoException(99L));

        mvc.perform(put("/productos/99").contentType(MediaType.APPLICATION_JSON).content(JSON_VALIDO))
                .andExpect(status().isNotFound());
    }

    @Test
    void eliminarDevuelve204() throws Exception {
        mvc.perform(delete("/productos/1")).andExpect(status().isNoContent());

        verify(servicio).eliminar(1L);
    }

    @Test
    void descontarStockDevuelveProductoActualizado() throws Exception {
        when(servicio.descontarStock(1L, 5)).thenReturn(new Producto("Cable HDMI", null, new BigDecimal("8.50"), 25));

        mvc.perform(patch("/productos/1/stock").contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(25));
    }

    @Test
    void descontarStockInsuficienteDevuelve409() throws Exception {
        when(servicio.descontarStock(1L, 500)).thenThrow(new StockInsuficienteException(1L, 500));

        mvc.perform(patch("/productos/1/stock").contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":500}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(header().string("X-Instance-Id", "test-1"));
    }

    @Test
    void descontarStockConCantidadInvalidaDevuelve400() throws Exception {
        mvc.perform(patch("/productos/1/stock").contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tipoDeContenidoNoSoportadoDevuelve415() throws Exception {
        mvc.perform(post("/productos").contentType(MediaType.TEXT_PLAIN).content("hola"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void errorInesperadoDevuelve500SinDetallesInternos() throws Exception {
        when(servicio.listar()).thenThrow(new IllegalStateException("detalle interno"));

        mvc.perform(get("/productos"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Error interno del servidor"));
    }
}
