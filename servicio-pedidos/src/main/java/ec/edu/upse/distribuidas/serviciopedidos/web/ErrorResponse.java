package ec.edu.upse.distribuidas.serviciopedidos.web;

import java.time.Instant;

/** Cuerpo uniforme de todas las respuestas de error. */
public record ErrorResponse(Instant timestamp, int status, String error, String message, String path) {
}
