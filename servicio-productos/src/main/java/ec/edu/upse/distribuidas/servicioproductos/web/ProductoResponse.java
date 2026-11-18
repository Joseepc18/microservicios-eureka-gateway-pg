package ec.edu.upse.distribuidas.servicioproductos.web;

import ec.edu.upse.distribuidas.servicioproductos.dominio.Producto;
import java.math.BigDecimal;

public record ProductoResponse(Long id, String nombre, String descripcion, BigDecimal precio, Integer stock) {

    public static ProductoResponse de(Producto p) {
        return new ProductoResponse(p.getId(), p.getNombre(), p.getDescripcion(), p.getPrecio(), p.getStock());
    }
}
