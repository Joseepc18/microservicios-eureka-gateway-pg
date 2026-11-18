package ec.edu.upse.distribuidas.serviciopedidos.cliente;

import java.math.BigDecimal;

/** Vista mínima de un producto de servicio-productos (se ignoran los demás campos). */
public record ProductoDto(Long id, String nombre, BigDecimal precio, Integer stock) {
}
