package ec.edu.upse.distribuidas.servicioproductos.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.edu.upse.distribuidas.servicioproductos.dominio.Producto;
import ec.edu.upse.distribuidas.servicioproductos.excepcion.ProductoNoEncontradoException;
import ec.edu.upse.distribuidas.servicioproductos.excepcion.StockInsuficienteException;
import ec.edu.upse.distribuidas.servicioproductos.web.ProductoRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Ejecuta las migraciones de Flyway reales sobre H2; cada prueba se revierte al terminar. */
@SpringBootTest
@Transactional
class ProductoServiceTests {

    @Autowired
    private ProductoService servicio;

    private static ProductoRequest datos(String nombre, int stock) {
        return new ProductoRequest(nombre, "descripción", new BigDecimal("10.00"), stock);
    }

    @Test
    void lasMigracionesCarganDatosDeEjemplo() {
        assertThat(servicio.listar()).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void crearYObtener() {
        Producto creado = servicio.crear(datos("Webcam", 7));

        Producto leido = servicio.obtener(creado.getId());

        assertThat(leido.getNombre()).isEqualTo("Webcam");
        assertThat(leido.getStock()).isEqualTo(7);
    }

    @Test
    void obtenerInexistenteLanzaNoEncontrado() {
        assertThatThrownBy(() -> servicio.obtener(-1L)).isInstanceOf(ProductoNoEncontradoException.class);
    }

    @Test
    void actualizarCambiaLosCampos() {
        Producto creado = servicio.crear(datos("Hub USB", 3));

        Producto actualizado = servicio.actualizar(creado.getId(), datos("Hub USB-C", 9));

        assertThat(actualizado.getNombre()).isEqualTo("Hub USB-C");
        assertThat(actualizado.getStock()).isEqualTo(9);
    }

    @Test
    void eliminarQuitaElProducto() {
        Producto creado = servicio.crear(datos("Temporal", 1));

        servicio.eliminar(creado.getId());

        assertThatThrownBy(() -> servicio.obtener(creado.getId()))
                .isInstanceOf(ProductoNoEncontradoException.class);
    }

    @Test
    void eliminarInexistenteLanzaNoEncontrado() {
        assertThatThrownBy(() -> servicio.eliminar(-1L)).isInstanceOf(ProductoNoEncontradoException.class);
    }

    @Test
    void descontarStockRestaLaCantidad() {
        Producto creado = servicio.crear(datos("Pila", 10));

        Producto resultado = servicio.descontarStock(creado.getId(), 4);

        assertThat(resultado.getStock()).isEqualTo(6);
    }

    @Test
    void descontarTodoElStockDisponibleEsValido() {
        Producto creado = servicio.crear(datos("Pila AA", 5));

        assertThat(servicio.descontarStock(creado.getId(), 5).getStock()).isZero();
    }

    @Test
    void descontarMasDelStockLanzaConflictoYNoModificaNada() {
        Producto creado = servicio.crear(datos("Cargador", 3));

        assertThatThrownBy(() -> servicio.descontarStock(creado.getId(), 4))
                .isInstanceOf(StockInsuficienteException.class);
        assertThat(servicio.obtener(creado.getId()).getStock()).isEqualTo(3);
    }

    @Test
    void descontarStockDeProductoInexistenteLanzaNoEncontrado() {
        assertThatThrownBy(() -> servicio.descontarStock(-1L, 1))
                .isInstanceOf(ProductoNoEncontradoException.class);
    }
}
