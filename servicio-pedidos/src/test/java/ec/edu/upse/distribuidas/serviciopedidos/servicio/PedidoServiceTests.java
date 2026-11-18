package ec.edu.upse.distribuidas.serviciopedidos.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ec.edu.upse.distribuidas.serviciopedidos.cliente.ProductoDto;
import ec.edu.upse.distribuidas.serviciopedidos.cliente.ProductosClient;
import ec.edu.upse.distribuidas.serviciopedidos.dominio.EstadoPedido;
import ec.edu.upse.distribuidas.serviciopedidos.dominio.Pedido;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.PedidoNoCancelableException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.PedidoNoEncontradoException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ProductoNoEncontradoException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ServicioProductosNoDisponibleException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.StockInsuficienteException;
import ec.edu.upse.distribuidas.serviciopedidos.repositorio.PedidoRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Migraciones de Flyway reales sobre H2 y servicio-productos simulado. */
@SpringBootTest
class PedidoServiceTests {

    @Autowired
    private PedidoService servicio;

    @Autowired
    private PedidoRepository repositorio;

    @MockitoBean
    private ProductosClient productos;

    @BeforeEach
    void limpiar() {
        repositorio.deleteAll();
    }

    private static ProductoDto producto(int stock) {
        return new ProductoDto(1L, "Mouse", new BigDecimal("12.50"), stock);
    }

    @Test
    void crearCalculaElTotalDescuentaStockYGuarda() {
        when(productos.obtener(1L)).thenReturn(producto(10));

        Pedido pedido = servicio.crear(1L, 4);

        assertThat(pedido.getTotal()).isEqualByComparingTo("50.00");
        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.CREADO);
        assertThat(repositorio.count()).isEqualTo(1);
        verify(productos).descontarStock(1L, 4);
    }

    @Test
    void stockInsuficienteEnLaConsultaNoDescuentaNiGuarda() {
        when(productos.obtener(1L)).thenReturn(producto(2));

        assertThatThrownBy(() -> servicio.crear(1L, 3)).isInstanceOf(StockInsuficienteException.class);

        verify(productos, never()).descontarStock(anyLong(), anyInt());
        assertThat(repositorio.count()).isZero();
    }

    @Test
    void conflictoAlDescontarNoGuardaElPedido() {
        when(productos.obtener(1L)).thenReturn(producto(5));
        doThrow(new StockInsuficienteException(1L, 5)).when(productos).descontarStock(1L, 5);

        assertThatThrownBy(() -> servicio.crear(1L, 5)).isInstanceOf(StockInsuficienteException.class);

        assertThat(repositorio.count()).isZero();
    }

    @Test
    void productoInexistenteNoGuardaNada() {
        when(productos.obtener(9L)).thenThrow(new ProductoNoEncontradoException(9L));

        assertThatThrownBy(() -> servicio.crear(9L, 1)).isInstanceOf(ProductoNoEncontradoException.class);

        assertThat(repositorio.count()).isZero();
    }

    @Test
    void servicioDeProductosCaidoNoGuardaNada() {
        when(productos.obtener(1L)).thenThrow(new ServicioProductosNoDisponibleException("timeout", null));

        assertThatThrownBy(() -> servicio.crear(1L, 1)).isInstanceOf(ServicioProductosNoDisponibleException.class);

        assertThat(repositorio.count()).isZero();
    }

    @Test
    void obtenerYListar() {
        when(productos.obtener(1L)).thenReturn(producto(10));
        Pedido creado = servicio.crear(1L, 1);

        assertThat(servicio.obtener(creado.getId()).getProductoId()).isEqualTo(1L);
        assertThat(servicio.listar()).hasSize(1);
    }

    @Test
    void obtenerInexistenteLanzaNoEncontrado() {
        assertThatThrownBy(() -> servicio.obtener(-1L)).isInstanceOf(PedidoNoEncontradoException.class);
    }

    @Test
    void cancelarCambiaElEstado() {
        when(productos.obtener(1L)).thenReturn(producto(10));
        Pedido creado = servicio.crear(1L, 1);

        Pedido cancelado = servicio.cancelar(creado.getId());

        assertThat(cancelado.getEstado()).isEqualTo(EstadoPedido.CANCELADO);
        assertThat(servicio.obtener(creado.getId()).getEstado()).isEqualTo(EstadoPedido.CANCELADO);
    }

    @Test
    void cancelarDosVecesLanzaConflicto() {
        when(productos.obtener(1L)).thenReturn(producto(10));
        Pedido creado = servicio.crear(1L, 1);
        servicio.cancelar(creado.getId());

        assertThatThrownBy(() -> servicio.cancelar(creado.getId())).isInstanceOf(PedidoNoCancelableException.class);
    }

    @Test
    void cancelarInexistenteLanzaNoEncontrado() {
        assertThatThrownBy(() -> servicio.cancelar(-1L)).isInstanceOf(PedidoNoEncontradoException.class);
    }
}
