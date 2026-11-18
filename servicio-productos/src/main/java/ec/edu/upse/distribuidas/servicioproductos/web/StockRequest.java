package ec.edu.upse.distribuidas.servicioproductos.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Cantidad a descontar del stock. */
public record StockRequest(@NotNull @Min(1) Integer cantidad) {
}
