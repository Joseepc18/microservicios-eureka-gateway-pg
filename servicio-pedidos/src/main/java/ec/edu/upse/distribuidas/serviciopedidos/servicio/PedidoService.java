package ec.edu.upse.distribuidas.serviciopedidos.servicio;

import ec.edu.upse.distribuidas.persistencerouting.ReadFromPrimary;
import ec.edu.upse.distribuidas.serviciopedidos.cliente.ProductoDto;
import ec.edu.upse.distribuidas.serviciopedidos.cliente.ProductosClient;
import ec.edu.upse.distribuidas.serviciopedidos.dominio.EstadoPedido;
import ec.edu.upse.distribuidas.serviciopedidos.dominio.Pedido;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.PedidoNoCancelableException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.PedidoNoEncontradoException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.StockInsuficienteException;
import ec.edu.upse.distribuidas.serviciopedidos.repositorio.PedidoRepository;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    private final PedidoRepository repositorio;
    private final ProductosClient productos;

    public PedidoService(PedidoRepository repositorio, ProductosClient productos) {
        this.repositorio = repositorio;
        this.productos = productos;
    }

    /**
     * Crea un pedido: consulta el producto, valida el stock, lo descuenta y guarda el pedido.
     *
     * <p>No es una transacción distribuida: si el pedido no se puede guardar después de descontar el
     * stock, el descuento queda aplicado en servicio-productos (ver docs/api.md). Por eso este método
     * no abre una transacción de base de datos: no debe mantenerse una conexión abierta durante las
     * llamadas HTTP.
     */
    public Pedido crear(Long productoId, int cantidad) {
        ProductoDto producto = productos.obtener(productoId);
        if (producto.stock() < cantidad) {
            throw new StockInsuficienteException(productoId, cantidad);
        }
        // El descuento es la verificación definitiva: otra solicitud pudo consumir el stock entre
        // la consulta y este paso, y entonces servicio-productos responde 409.
        productos.descontarStock(productoId, cantidad);

        BigDecimal total = producto.precio().multiply(BigDecimal.valueOf(cantidad));
        try {
            Pedido pedido = repositorio.save(new Pedido(productoId, cantidad, total));
            log.info("Pedido {} creado: productoId={} cantidad={} total={}",
                    pedido.getId(), productoId, cantidad, total);
            return pedido;
        } catch (RuntimeException e) {
            log.error("Stock descontado pero el pedido no se guardó: productoId={} cantidad={}. "
                    + "Estado inconsistente que requiere corrección manual.", productoId, cantidad, e);
            throw e;
        }
    }

    /**
     * Lectura ordinaria: se atiende en una réplica. Acepta consistencia eventual: un pedido creado
     * hace un instante podría no estar todavía en la réplica (404). Para leer lo recién escrito,
     * {@link #obtenerDesdePrimario}.
     */
    @Transactional(readOnly = true)
    public Pedido obtener(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new PedidoNoEncontradoException(id));
    }

    /**
     * Lectura crítica: se atiende desde el primario aunque sea de solo lectura, porque debe ver una
     * escritura anterior (por ejemplo, consultar el pedido justo después de crearlo). Es la que usa
     * {@code GET /pedidos/{id}?consistencia=fuerte}.
     */
    @ReadFromPrimary
    @Transactional(readOnly = true)
    public Pedido obtenerDesdePrimario(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new PedidoNoEncontradoException(id));
    }

    @Transactional(readOnly = true)
    public List<Pedido> listar() {
        return repositorio.findAll();
    }

    /** Solo cambia el estado a CANCELADO; servicio-productos no ofrece una operación para devolver stock. */
    @Transactional
    public Pedido cancelar(Long id) {
        Pedido pedido = repositorio.findById(id).orElseThrow(() -> new PedidoNoEncontradoException(id));
        if (pedido.getEstado() != EstadoPedido.CREADO) {
            throw new PedidoNoCancelableException(id, pedido.getEstado());
        }
        pedido.cancelar();
        log.info("Pedido {} cancelado", id);
        return pedido;
    }
}
