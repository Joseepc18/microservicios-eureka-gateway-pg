package ec.edu.upse.distribuidas.servicioproductos.web;

import ec.edu.upse.distribuidas.servicioproductos.excepcion.ProductoNoEncontradoException;
import ec.edu.upse.distribuidas.servicioproductos.excepcion.StockInsuficienteException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Respuestas de error JSON uniformes: 400, 404, 405, 409 y 500. */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(ProductoNoEncontradoException.class)
    public ResponseEntity<ErrorResponse> productoNoEncontrado(ProductoNoEncontradoException ex,
                                                              HttpServletRequest req) {
        return responder(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> rutaNoEncontrada(HttpServletRequest req) {
        return responder(HttpStatus.NOT_FOUND, "Recurso no encontrado", req);
    }

    @ExceptionHandler(StockInsuficienteException.class)
    public ResponseEntity<ErrorResponse> conflicto(StockInsuficienteException ex, HttpServletRequest req) {
        return responder(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validacion(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detalle = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return responder(HttpStatus.BAD_REQUEST, detalle, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> cuerpoInvalido(HttpServletRequest req) {
        return responder(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud no es un JSON válido", req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> parametroInvalido(MethodArgumentTypeMismatchException ex,
                                                           HttpServletRequest req) {
        return responder(HttpStatus.BAD_REQUEST, "Valor inválido para '" + ex.getName() + "'", req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> metodoNoPermitido(HttpRequestMethodNotSupportedException ex,
                                                           HttpServletRequest req) {
        return responder(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> inesperado(Exception ex, HttpServletRequest req) {
        // Las excepciones web de Spring (415, 406, parámetros faltantes, etc.) ya traen su código HTTP.
        if (ex instanceof org.springframework.web.ErrorResponse web) {
            HttpStatus estado = HttpStatus.valueOf(web.getStatusCode().value());
            return responder(estado, estado.getReasonPhrase(), req);
        }
        log.error("Error no controlado en {}", req.getRequestURI(), ex);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor", req);
    }

    private ResponseEntity<ErrorResponse> responder(HttpStatus estado, String mensaje, HttpServletRequest req) {
        return ResponseEntity.status(estado).body(new ErrorResponse(
                Instant.now(), estado.value(), estado.getReasonPhrase(), mensaje, req.getRequestURI()));
    }
}
