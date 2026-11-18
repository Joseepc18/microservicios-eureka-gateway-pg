package ec.edu.upse.distribuidas.servicioproductos.web;

import java.time.Instant;

/** Cuerpo uniforme de todas las respuestas de error. */
public record ErrorResponse(Instant timestamp, int status, String error, String message, String path) {
}
