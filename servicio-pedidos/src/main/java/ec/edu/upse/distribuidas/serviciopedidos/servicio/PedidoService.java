package ec.edu.upse.distribuidas.serviciopedidos.servicio;

import ec.edu.upse.distribuidas.persistencerouting.PrimaryRead;
import ec.edu.upse.distribuidas.persistencerouting.ReadFromPrimary;
import ec.edu.upse.distribuidas.serviciopedidos.cliente.ProductoDto;
import ec.edu.upse.distribuidas.serviciopedidos.cliente.ProductosClient;
import ec.edu.upse.distribuidas.serviciopedidos.dominio.EstadoPedido;
import ec.edu.upse.distribuidas.serviciopedidos.dominio.Pedido;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.PedidoNoCancelableException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.PedidoNoEncontradoException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ProductoNoEncontradoException;
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
     * Lectura crítica: se atiende desde el primario, no desde una réplica, porque el cliente suele
     * consultar el pedido justo después de crearlo y una réplica con retraso aún podría no tenerlo.
     */
    @ReadFromPrimary
    @Transactional(readOnly = true)
    public Pedido obtener(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new PedidoNoEncontradoException(id));
    }

    @Transactional(readOnly = true)
    public List<Pedido> listar() {
        return repositorio.findAll();
    }

    /**
     * Cancela un pedido y repone su stock en servicio-productos.
     *
     * <ol>
     *   <li>Lee el pedido en el primario (lectura crítica): puede haberse creado hace un instante.</li>
     *   <li>Lo pasa de CREADO a CANCELADO con una sentencia condicional: si dos solicitudes lo
     *       cancelan a la vez, solo una lo consigue y solo esa repone el stock.</li>
     *   <li>Repone el stock con una llamada HTTP a servicio-productos.</li>
     * </ol>
     *
     * <p>Como en {@link #crear}, no hay una transacción que abarque la llamada HTTP (son dos bases
     * distintas). Si servicio-productos no responde, el pedido vuelve a CREADO y se responde 503
     * para que el cliente reintente la cancelación. Si el producto ya no existe, el pedido queda
     * cancelado igualmente: no hay stock que reponer.
     */
    public Pedido cancelar(Long id) {
        Pedido pedido = PrimaryRead.call(() -> repositorio.findById(id))
                .orElseThrow(() -> new PedidoNoEncontradoException(id));
        if (pedido.getEstado() != EstadoPedido.CREADO
                || repositorio.cambiarEstado(id, EstadoPedido.CREADO, EstadoPedido.CANCELADO) == 0) {
            // Ya estaba cancelado, o lo canceló otra solicitud entre la lectura y este paso.
            throw new PedidoNoCancelableException(id, EstadoPedido.CANCELADO);
        }

        try {
            productos.reponerStock(pedido.getProductoId(), pedido.getCantidad());
            log.info("Pedido {} cancelado: stock repuesto productoId={} cantidad={}",
                    id, pedido.getProductoId(), pedido.getCantidad());
        } catch (ProductoNoEncontradoException e) {
            log.warn("Pedido {} cancelado: el producto {} ya no existe, no hay stock que reponer",
                    id, pedido.getProductoId());
        } catch (RuntimeException e) {
            revertirCancelacion(id);
            throw e;
        }
        pedido.cancelar();
        return pedido;
    }

    /** Devuelve el pedido a CREADO cuando no se pudo reponer el stock, para que se pueda reintentar. */
    private void revertirCancelacion(Long id) {
        try {
            repositorio.cambiarEstado(id, EstadoPedido.CANCELADO, EstadoPedido.CREADO);
            log.warn("Pedido {}: no se pudo reponer el stock; el pedido vuelve a CREADO", id);
        } catch (RuntimeException e) {
            log.error("Pedido {} cancelado sin reponer el stock y no se pudo revertir la cancelación. "
                    + "Estado inconsistente que requiere corrección manual.", id, e);
        }
    }
}
