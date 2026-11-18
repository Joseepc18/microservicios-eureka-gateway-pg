package ec.edu.upse.distribuidas.servicioproductos.web;

import ec.edu.upse.distribuidas.servicioproductos.servicio.ProductoService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/productos")
public class ProductoController {

    private final ProductoService servicio;

    public ProductoController(ProductoService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<ProductoResponse> listar() {
        return servicio.listar().stream().map(ProductoResponse::de).toList();
    }

    @GetMapping("/{id}")
    public ProductoResponse obtener(@PathVariable Long id) {
        return ProductoResponse.de(servicio.obtener(id));
    }

    @PostMapping
    public ResponseEntity<ProductoResponse> crear(@Valid @RequestBody ProductoRequest datos) {
        ProductoResponse creado = ProductoResponse.de(servicio.crear(datos));
        return ResponseEntity.created(URI.create("/productos/" + creado.id())).body(creado);
    }

    @PutMapping("/{id}")
    public ProductoResponse actualizar(@PathVariable Long id, @Valid @RequestBody ProductoRequest datos) {
        return ProductoResponse.de(servicio.actualizar(id, datos));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        servicio.eliminar(id);
        return ResponseEntity.noContent().build();
    }

    /** Descuenta stock (lo usa servicio-pedidos). 409 si el stock es insuficiente. */
    @PatchMapping("/{id}/stock")
    public ProductoResponse descontarStock(@PathVariable Long id, @Valid @RequestBody StockRequest datos) {
        return ProductoResponse.de(servicio.descontarStock(id, datos.cantidad()));
    }
}
