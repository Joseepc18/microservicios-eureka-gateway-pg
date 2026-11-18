package ec.edu.upse.distribuidas.serviciopedidos.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PedidoRequest(
        @NotNull @Positive Long productoId,
        @NotNull @Min(1) Integer cantidad) {
}
