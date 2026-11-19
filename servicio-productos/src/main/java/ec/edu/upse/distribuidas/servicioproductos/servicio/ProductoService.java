package ec.edu.upse.distribuidas.servicioproductos.servicio;

import ec.edu.upse.distribuidas.servicioproductos.dominio.Producto;
import ec.edu.upse.distribuidas.servicioproductos.excepcion.ProductoNoEncontradoException;
import ec.edu.upse.distribuidas.servicioproductos.excepcion.StockInsuficienteException;
import ec.edu.upse.distribuidas.servicioproductos.repositorio.ProductoRepository;
import ec.edu.upse.distribuidas.servicioproductos.web.ProductoRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lecturas con transacción de solo lectura; escrituras con transacción normal. */
@Service
@Transactional(readOnly = true)
public class ProductoService {

    private final ProductoRepository repositorio;

    public ProductoService(ProductoRepository repositorio) {
        this.repositorio = repositorio;
    }

    public List<Producto> listar() {
        return repositorio.findAll();
    }

    public Producto obtener(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new ProductoNoEncontradoException(id));
    }

    @Transactional
    public Producto crear(ProductoRequest datos) {
        return repositorio.save(new Producto(datos.nombre(), datos.descripcion(), datos.precio(), datos.stock()));
    }

    @Transactional
    public Producto actualizar(Long id, ProductoRequest datos) {
        Producto producto = obtener(id);
        producto.actualizar(datos.nombre(), datos.descripcion(), datos.precio(), datos.stock());
        return producto;
    }

    @Transactional
    public void eliminar(Long id) {
        if (!repositorio.existsById(id)) {
            throw new ProductoNoEncontradoException(id);
        }
        repositorio.deleteById(id);
    }

    /** Descuenta stock; 404 si el producto no existe y 409 si no alcanza. */
    @Transactional
    public Producto descontarStock(Long id, int cantidad) {
        if (repositorio.descontarStock(id, cantidad) == 0) {
            if (!repositorio.existsById(id)) {
                throw new ProductoNoEncontradoException(id);
            }
            throw new StockInsuficienteException(id, cantidad);
        }
        return obtener(id);
    }

    /** Repone stock (lo usa servicio-pedidos al cancelar un pedido); 404 si el producto no existe. */
    @Transactional
    public Producto reponerStock(Long id, int cantidad) {
        if (repositorio.reponerStock(id, cantidad) == 0) {
            throw new ProductoNoEncontradoException(id);
        }
        return obtener(id);
    }
}
